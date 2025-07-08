pipeline {
    agent any
    
    parameters {
        string(name: 'PROJECT_NAME', defaultValue: 'simplest-jenkins', description: 'Project name')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to analyze')
        choice(name: 'OUTPUT_FORMAT', choices: ['pretty', 'json', 'sarif'], description: 'Output format')
        string(name: 'DEFAULT_OWNER', defaultValue: 'plexicus', description: 'Default owner')
        string(name: 'REPOSITORY_ID', defaultValue: '', description: 'Repository ID')
        string(name: 'HOST_PROJECT_PATH', defaultValue: '/home/juanj/Documents/plexicus/simplest-jenkins', description: 'Host project path')
        string(name: 'HOST_MACHINE_HOSTNAME', defaultValue: '', description: 'Host machine hostname (leave empty to auto-detect)')
        booleanParam(name: 'AUTONOMOUS_SCAN', defaultValue: false, description: 'Autonomous scan')
    }
    
    environment {
        DOCKER_IMAGE = 'plexicus/plexalyzer:latest'
        CONFIG_PATH = '/app/config/default_config.yaml'
    }
    
    stages {
        stage('Setup Repository URL') {
            steps {
                script {
                    // Determine the host machine hostname
                    def hostname
                    if (params.HOST_MACHINE_HOSTNAME && params.HOST_MACHINE_HOSTNAME.trim() != '') {
                        hostname = params.HOST_MACHINE_HOSTNAME.trim()
                        echo "Using provided hostname: ${hostname}"
                    } else {
                        // Try to get hostname from host machine using Docker
                        try {
                            hostname = sh(
                                script: 'docker run --rm --network host alpine hostname',
                                returnStdout: true
                            ).trim()
                            echo "Auto-detected hostname: ${hostname}"
                        } catch (Exception e) {
                            echo "Warning: Could not auto-detect hostname, using 'localhost'"
                            hostname = 'localhost'
                        }
                    }
                    
                    env.REPOSITORY_URL = "plex://${hostname}${params.HOST_PROJECT_PATH}"
                    echo "Repository URL configured: ${env.REPOSITORY_URL}"
                }
            }
        }
        
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        
        stage('Pull Docker Image') {
            steps {
                script {
                    // Check if Docker is available
                    def dockerAvailable = sh(
                        script: 'which docker',
                        returnStatus: true
                    )
                    
                    if (dockerAvailable != 0) {
                        error "Docker is not available on this Jenkins agent. Please ensure Docker is installed and accessible."
                    }
                    
                    // Check Docker daemon status
                    def dockerDaemon = sh(
                        script: 'docker info',
                        returnStatus: true
                    )
                    
                    if (dockerDaemon != 0) {
                        error "Docker daemon is not running or not accessible. Please start Docker service."
                    }
                    
                    echo "Downloading Plexalyzer Docker image from Docker Hub..."
                    sh "docker pull ${DOCKER_IMAGE}"
                    echo "✅ Docker image downloaded successfully"
                }
            }
        }
        
        stage('Security Analysis') {
            steps {
                script {
                    // Generate timestamp for log file
                    def timestamp = new Date().format('yyyy-MM-dd_HH-mm-ss')
                    def logFileName = "plexalyzer_${timestamp}.log"
                    
                    def analysisCommand = """
                        docker run --rm \
                            -v "${params.HOST_PROJECT_PATH}:/mounted_volumes" \
                            -e MESSAGE_URL=https://api.app.dev.plexicus.ai/receive_plexalyzer_message \
                            -e PLEXALYZER_TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbGllbnRfaWQiOiI2NWYwNzlmM2VmODk4ZTZhNmJiMzdlNWIiLCJjcmVhdGVkX2F0IjoiMjAyNC0xMS0wOFQxODowMzo0Mi4yODEyODYifQ.xLegYmnZ7Yfvky5D2riNLwyAPkw3RidkKnk2f3vBeoE \
                            ${DOCKER_IMAGE} \
                            /venvs/plexicus-fastapi/bin/python /app/analyze.py \
                            --config "/mounted_volumes/plexalyzer/custom_config.yml" \
                            --name '${params.PROJECT_NAME ?: env.JOB_NAME}' \
                            --output '${params.OUTPUT_FORMAT}' \
                            --owner '${params.DEFAULT_OWNER}' \
                            --url '${env.REPOSITORY_URL}' \
                            --branch '${params.BRANCH_NAME ?: env.GIT_BRANCH}' \
                            --log_file '/mounted_volumes/plexalyzer/${logFileName}' \
                            --no-progress-bar \
                            ${params.REPOSITORY_ID ? '--repository_id ' + params.REPOSITORY_ID : ''} \
                            ${params.AUTONOMOUS_SCAN ? '--auto' : ''} > plexalyzer_output.txt 2>&1
                    """
                    
                    echo "Executing analysis command..."
                    echo "Log file will be saved as: ${logFileName}"
                    
                    def exitCode = sh(
                        script: analysisCommand,
                        returnStatus: true
                    )
                    
                    echo "Exit code: ${exitCode}"
                    
                    // Exit code indicates if vulnerabilities were found
                    env.ANALYSIS_EXIT_CODE = exitCode.toString()
                    
                    if (exitCode == 500) {
                        error "Fatal error in security analysis"
                    } else if (exitCode == 1) {
                        echo "Scan completed successfully"
                    } else if (exitCode == 0) {
                        echo "Scan completed successfully"
                    } else {
                        echo "❌ Analysis failed with exit code: ${exitCode}"
                        error "Analysis failed with exit code: ${exitCode}. Check the output for details."
                    }
                }
            }
        }
    }
    
    post {
        success {
            echo "✅ Security analysis completed successfully, you can check the status of the scan here: https://app.plexicus.ai/repositories"
        }
        
        failure {
            echo "❌ Security analysis failed"
        }
        
        unstable {
            echo "⚠️  Build marked as unstable due to vulnerabilities found"
        }
    }
}