pipeline {
    agent any
    
    environment {
        DOCKER_IMAGE = "vulnerable-webapp"
        DOCKER_TAG = "${BUILD_NUMBER}"
        DOCKER_REGISTRY = "your-registry"
    }
    
    tools {
        maven 'Maven-3.9.0'
        jdk 'JDK-17'
    }
    
    stages {
        stage('Checkout') {
            steps {
                echo 'Checking out source code...'
                checkout scm
                
                script {
                    env.GIT_COMMIT_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        
        stage('Security Analysis - Plexalyzer') {
            steps {
                echo 'Running security analysis with Plexalyzer...'
                
                script {
                    // Call plexalyzer
                    // This should be configured manually according to your plexalyzer setup
                    // Example configuration might include:
                    // - Setting up plexalyzer credentials
                    // - Configuring scan parameters
                    // - Setting up result processing
                    
                    echo "TODO: Configure plexalyzer with proper credentials and scan parameters"
                    echo "Plexalyzer should analyze the code for vulnerabilities before build"
                    
                    // Placeholder for actual plexalyzer call
                    // sh 'plexalyzer scan --project ${JOB_NAME} --branch ${BRANCH_NAME} --commit ${GIT_COMMIT}'
                }
            }
            
            post {
                always {
                    echo 'Security analysis completed'
                    // Call plexalyzer
                    // Archive plexalyzer reports if available
                    // archiveArtifacts artifacts: 'plexalyzer-report.xml', allowEmptyArchive: true
                }
            }
        }
        
        stage('Build') {
            steps {
                echo 'Building the application...'
                sh 'mvn clean compile'
            }
        }
        
        stage('Test') {
            steps {
                echo 'Running unit tests...'
                sh 'mvn test'
            }
            
            post {
                always {
                    publishTestResults testResultsPattern: 'target/surefire-reports/*.xml'
                    publishCoverage adapters: [jacocoAdapter('target/site/jacoco/jacoco.xml')], sourceFileResolver: sourceFiles('NEVER_STORE')
                }
            }
        }
        
        stage('Package') {
            steps {
                echo 'Packaging the application...'
                sh 'mvn package -DskipTests'
            }
            
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }
        
        stage('Docker Build') {
            steps {
                echo 'Building Docker image...'
                script {
                    def dockerImage = docker.build("${DOCKER_IMAGE}:${DOCKER_TAG}")
                    docker.withRegistry('', 'docker-registry-credentials') {
                        dockerImage.push()
                        dockerImage.push('latest')
                    }
                }
            }
        }
        
        stage('Security Scan - Post Build') {
            steps {
                echo 'Running post-build security scan...'
                
                script {
                    // Call plexalyzer
                    // Post-build security analysis
                    echo "TODO: Configure post-build plexalyzer scan for the built artifact"
                    
                    // Placeholder for docker image security scan
                    // sh "plexalyzer docker-scan --image ${DOCKER_IMAGE}:${DOCKER_TAG}"
                }
            }
        }
        
        stage('Deploy to Staging') {
            when {
                branch 'main'
            }
            
            steps {
                echo 'Deploying to staging environment...'
                
                script {
                    // Deploy using docker-compose
                    sh '''
                        docker-compose -f docker-compose.staging.yml down || true
                        docker-compose -f docker-compose.staging.yml pull
                        docker-compose -f docker-compose.staging.yml up -d
                    '''
                }
            }
        }
        
        stage('Deploy to Production') {
            when {
                allOf {
                    branch 'main'
                    expression { params.DEPLOY_TO_PRODUCTION == true }
                }
            }
            
            steps {
                echo 'Deploying to production environment on Hetzner...'
                
                script {
                    // Deploy to Hetzner using docker-compose
                    sshagent(['hetzner-ssh-key']) {
                        sh '''
                            ssh -o StrictHostKeyChecking=no user@your-hetzner-server.com '
                                cd /opt/vulnerable-webapp &&
                                docker-compose down &&
                                docker-compose pull &&
                                docker-compose up -d
                            '
                        '''
                    }
                }
            }
        }
    }
    
    post {
        always {
            echo 'Pipeline execution completed'
            
            // Call plexalyzer
            // Send results to plexalyzer for tracking
            script {
                echo "TODO: Send final pipeline results to plexalyzer"
                // sh "plexalyzer report --pipeline-id ${BUILD_ID} --status ${currentBuild.result}"
            }
            
            // Cleanup
            sh 'docker system prune -f || true'
        }
        
        success {
            echo 'Pipeline completed successfully!'
            // Notify success
        }
        
        failure {
            echo 'Pipeline failed!'
            // Notify failure
        }
    }
    
    parameters {
        booleanParam(
            name: 'DEPLOY_TO_PRODUCTION',
            defaultValue: false,
            description: 'Deploy to production environment'
        )
    }
} 