pipeline {
    agent {
        kubernetes {
            yaml '''
                apiVersion: v1
                kind: Pod
                spec:
                  containers:
                  - name: tools
                    image: alpine/k8s:1.27.4
                    command: ['cat']
                    tty: true
                    resources:
                      requests:
                        cpu: "100m"
                        memory: "128Mi"
                  - name: jnlp
                    image: jenkins/inbound-agent:latest
                    args: ['$(JENKINS_SECRET)', '$(JENKINS_NAME)']
                    resources:
                      requests:
                        cpu: "50m"
                        memory: "256Mi"
            '''
        }
    }

    environment {
        KUBECONFIG = credentials('kubeconfig-secret-id')
        DB_USER = "root"
        DB_PASS = "root"
        DB_NAME = "notepaddb"
        SQL_FILE = "notepaddb.sql"
        NAMESPACE = "default"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/lilitbet/kuber.git'
            }
        }

        stage('Deploy MySQL') {
            steps {
                container('tools') {
                    echo 'Деплой MySQL в Kubernetes...'
                    sh '''
                        kubectl apply -f pv-nfs.yaml -n ${NAMESPACE}
                        kubectl apply -f pvc-nfs.yaml -n ${NAMESPACE}
                        kubectl apply -f mysql-deployment.yaml -n ${NAMESPACE}
                        kubectl apply -f mysql-service.yaml -n ${NAMESPACE}
                        echo "MySQL deployment применён"
                    '''
                }
            }
        }

        stage('Wait for MySQL') {
            steps {
                container('tools') {
                    echo 'Ждём пока MySQL станет доступен...'
                    script {
                        def mysqlReady = false
                        def attempts = 0
                        def maxAttempts = 30

                        while (!mysqlReady && attempts < maxAttempts) {
                            def podStatus = sh(
                                script: "kubectl get pods -n ${NAMESPACE} -l app=mysql -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo 'NotFound'",
                                returnStdout: true
                            ).trim()

                            if (podStatus == "Running") {
                                // Проверяем готовность через readiness probe
                                def readyStatus = sh(
                                    script: "kubectl get pods -n ${NAMESPACE} -l app=mysql -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || echo 'false'",
                                    returnStdout: true
                                ).trim()

                                if (readyStatus == "true") {
                                    // Дополнительная проверка через mysqladmin ping
                                    def pingResult = sh(
                                        script: "kubectl exec -n ${NAMESPACE} deployment/mysql -- mysqladmin ping -u${DB_USER} -p${DB_PASS} --silent 2>/dev/null",
                                        returnStatus: true
                                    )

                                    if (pingResult == 0) {
                                        mysqlReady = true
                                        echo "MySQL готов к подключениям"
                                    } else {
                                        echo "MySQL pod запущен, но ещё не готов, ждём 5 секунд..."
                                        sleep(time: 5, unit: "SECONDS")
                                    }
                                } else {
                                    echo "MySQL pod запущен, но контейнер ещё не готов, ждём 5 секунд..."
                                    sleep(time: 5, unit: "SECONDS")
                                }
                            } else {
                                echo "MySQL ещё не готов (статус: ${podStatus}), ждём 5 секунд..."
                                sleep(time: 5, unit: "SECONDS")
                            }
                            attempts++
                        }

                        if (!mysqlReady) {
                            echo "MySQL не стал доступен за отведённое время"
                            sh "kubectl get pods -n ${NAMESPACE} -l app=mysql -o wide"
                            sh "kubectl describe pods -n ${NAMESPACE} -l app=mysql"
                            error("MySQL не готов! Деплой остановлен.")
                        }
                    }
                }
            }
        }

        stage('Load SQL from GitHub') {
            steps {
                container('tools') {
                    echo "Загружаем SQL из репозитория и создаём таблицу users..."
                    script {
                        // Проверяем наличие SQL файла в репозитории
                        if (fileExists("dump/${SQL_FILE}")) {
                            sh """
                                kubectl exec -n ${NAMESPACE} deployment/mysql -- mysql -u${DB_USER} -p${DB_PASS} -e "DROP TABLE IF EXISTS ${DB_NAME}.pages;DROP TABLE IF EXISTS ${DB_NAME}.users;"
                                kubectl exec -i -n ${NAMESPACE} deployment/mysql -- mysql -u${DB_USER} -p${DB_PASS} ${DB_NAME} < dump/${SQL_FILE}
                                echo "SQL из ${SQL_FILE} загружен в MySQL"
                            """
                        } else {
                            echo "Файл ${SQL_FILE} не найден в репозитории, пропускаем этап"
                        }
                    }
                }
            }
        }

        stage('Verify Pages Table Schema') {
            steps {
                container('tools') {
                    script {
                        echo "Проверка структуры таблицы pages..."
        
                        // Выполняем DESCRIBE и извлекаем имена колонок
                        def describeOutput = sh(
                            script: """
                                kubectl exec -n ${NAMESPACE} deployment/mysql -- mysql -u${DB_USER} -p${DB_PASS} -D ${DB_NAME} -e 'DESCRIBE pages;' 2>/dev/null | tail -n +2 | awk '{print \$1}'
                            """,
                            returnStdout: true
                        ).trim()
        
                        if (!describeOutput) {
                            error("Не удалось получить описание таблицы pages. Возможно, таблица не существует.")
                        }
        
                        // Преобразуем вывод в список
                        def actualColumns = describeOutput.readLines().collect { it.trim() }
                        echo "Фактические колонки: ${actualColumns}"
        
                        // Ожидаемые колонки из SQL дампа
                        def expectedColumns = ['id', 'userId', 'title', 'text_crop', 'text']
        
                        // Проверка количества
                        if (actualColumns.size() != expectedColumns.size()) {
                            error("Неверное количество колонок. Ожидалось: ${expectedColumns.size()}, получено: ${actualColumns.size()}")
                        }
        
                        // Проверка наличия всех ожидаемых и отсутствия лишних
                        def missingColumns = expectedColumns - actualColumns
                        def extraColumns = actualColumns - expectedColumns
        
                        if (missingColumns) {
                            echo "Отсутствуют колонки: ${missingColumns}"
                        }
                        if (extraColumns) {
                            echo "Лишние колонки: ${extraColumns}"
                        }
                        if (missingColumns || extraColumns) {
                            error("Структура таблицы pages не соответствует ожидаемой")
                        }
        
                        echo "Структура таблицы pages полностью соответствует."
                    }
                }
            }
        }

        stage('Deploy Application') {
            steps {
                container('tools') {
                    echo 'Деплой приложения crudback в Kubernetes...'
                    sh '''
                        kubectl apply -f app-deployment.yaml -n ${NAMESPACE}
                        kubectl apply -f app-service.yaml -n ${NAMESPACE}
                        echo "Приложение crudback deployment применён"
                    '''
                    
                    echo "Ожидаем готовности deployment..."
                    sh "kubectl rollout status deployment/crudback-app -n ${NAMESPACE} --timeout=120s"
                    sh "kubectl get deployments -n ${NAMESPACE}"
                }
            }
        }

        stage('Verify Deployment') {
            steps {
                container('tools') {
                    echo 'Проверка работоспособности приложения...'
                    script {
                        def podCount = sh(
                            script: "kubectl get pods -n ${NAMESPACE} -l app=crudback --no-headers 2>/dev/null | wc -l",
                            returnStdout: true
                        ).trim()

                        if (podCount.toInteger() == 0) {
                            echo "Поды приложения не найдены!"
                            sh "kubectl get pods -n ${NAMESPACE} -l app=crudback -o wide"
                            error("Деплой неуспешен - поды приложения не запущены!")
                        } else {
                            echo "Найдено ${podCount} подов приложения"
                            sh "kubectl get pods -n ${NAMESPACE} -l app=crudback -o wide"
                            
                            // Проверяем готовность подов
                            def readyPods = sh(
                                script: "kubectl get pods -n ${NAMESPACE} -l app=crudback -o jsonpath='{.items[*].status.containerStatuses[*].ready}' | grep -o true | wc -l",
                                returnStdout: true
                            ).trim()
                            
                            echo "Готовых подов: ${readyPods}/${podCount}"
                            
                            if (readyPods.toInteger() == 0) {
                                echo "Поды не готовы к работе!"
                                sh "kubectl describe pods -n ${NAMESPACE} -l app=crudback"
                                error("Деплой неуспешен - поды не готовы!")
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            echo "Пайплайн завершён"
            container('tools') {
                echo "=== ДИАГНОСТИЧЕСКАЯ ИНФОРМАЦИЯ ==="
                sh "kubectl get all -n ${NAMESPACE} || true"
            }
        }
        success {
            echo 'Пайплайн успешно выполнен'
        }
        failure {
            echo 'Пайплайн завершился с ошибкой'
            container('tools') {
                echo "=== ДИАГНОСТИКА ОШИБОК ==="
                sh "kubectl get pods -n ${NAMESPACE} -o wide || true"
                sh "kubectl describe deployment mysql -n ${NAMESPACE} || true"
                sh "kubectl describe deployment crudback-app -n ${NAMESPACE} || true"
                sh "kubectl logs -n ${NAMESPACE} -l app=mysql --tail=50 || true"
                sh "kubectl logs -n ${NAMESPACE} -l app=crudback --tail=50 || true"
            }
        }
    }
}

