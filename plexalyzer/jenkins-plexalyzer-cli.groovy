pipeline {
    agent any
    
    parameters {
        string(name: 'PROJECT_NAME', defaultValue: 'simplest-jenkins', description: 'Project name')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to analyze')
        choice(name: 'OUTPUT_FORMAT', choices: ['pretty', 'json', 'sarif'], description: 'Output format')
        string(name: 'DEFAULT_OWNER', defaultValue: 'plexicus', description: 'Default owner')
        string(name: 'REPOSITORY_ID', defaultValue: '', description: 'Repository ID')
        string(name: 'HOST_PROJECT_PATH', defaultValue: '${env.HOSTNAME}${env.WORKSPACE}', description: 'Host project path')
        string(name: 'HOST_MACHINE_HOSTNAME', defaultValue: '', description: 'Host machine hostname (leave empty to auto-detect)')
        booleanParam(name: 'AUTONOMOUS_SCAN', defaultValue: false, description: 'Autonomous scan')
        booleanParam(name: 'ONLY_GIT_CHANGES', defaultValue: false, description: 'Only analyze changed files in Git repository')
    }
    
    environment {
        DOCKER_IMAGE = 'plexicus/plexalyzer:latest'
        CONFIG_PATH = '/app/config/default_config.yaml'
    }
    
    stages {
        stage('Setup Repository URL') {
            steps {
                script {
                    echo "Container hostname: ${env.HOSTNAME}"
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
        
        stage('Prepare Changed Files') {
            when {
                expression {
                    return params.ONLY_GIT_CHANGES || (params.REPOSITORY_ID && params.REPOSITORY_ID.trim() != '')
                }
            }
            steps {
                script {
                    echo "Analyzing only changed files..."
                    
                    // Get the list of changed files using Docker to run git in the HOST_PROJECT_PATH
                    def changedFiles = ""
                    
                    try {
                        echo "Getting changed files from ${params.HOST_PROJECT_PATH} using Docker..."
                        
                        // First, check if it's a valid git repository
                        def gitCheck = sh(
                            script: """
                                docker run --rm \
                                    -v "${params.HOST_PROJECT_PATH}:/repo" \
                                    -w /repo \
                                    alpine \
                                    sh -c 'apk add --no-cache git && git config --global --add safe.directory /repo && git status --porcelain'
                            """,
                            returnStatus: true
                        )
                        
                        if (gitCheck != 0) {
                            echo "Not a valid git repository in ${params.HOST_PROJECT_PATH}"
                            echo "Falling back to full repository analysis"
                            env.ANALYZE_CHANGED_FILES = 'false'
                            return
                        }
                        
                        // Get changed files
                        changedFiles = sh(
                            script: """
                                docker run --rm \
                                    -v "${params.HOST_PROJECT_PATH}:/repo" \
                                    -w /repo \
                                    alpine \
                                    sh -c 'apk add --no-cache git >/dev/null 2>&1 && git config --global --add safe.directory /repo && git diff --name-only HEAD~1..HEAD'
                            """,
                            returnStdout: true
                        ).trim()
                        
                        echo "Git diff executed successfully"
                        
                    } catch (Exception gitError) {
                        echo "Git diff failed: ${gitError.message}"
                        echo "Falling back to full repository analysis"
                        env.ANALYZE_CHANGED_FILES = 'false'
                        return
                    }
                    
                    if (!changedFiles || changedFiles.trim() == '') {
                        echo "No changed files detected. Exiting."
                        currentBuild.result = 'SUCCESS'
                        return
                    }
                    
                    echo "Changed files found:"
                    echo changedFiles
                    
                    // Create plexalyzer directory and write changed files using Docker on the host
                    def changedFilesContent = changedFiles.split('\n').findAll { it.trim() != '' }.join('\n')
                    def changedFilesPath = "${params.HOST_PROJECT_PATH}/plexalyzer/changed_files.txt"
                    
                    // Create the file on the host using Docker
                    sh """
                        docker run --rm \
                            -v "${params.HOST_PROJECT_PATH}:/host_project" \
                            alpine \
                            sh -c 'mkdir -p /host_project/plexalyzer && echo "${changedFilesContent}" > /host_project/plexalyzer/changed_files.txt'
                    """
                    
                    // Set environment variable to indicate we're using changed files
                    env.ANALYZE_CHANGED_FILES = 'true'
                    env.CHANGED_FILES_COUNT = changedFiles.split('\n').findAll { it.trim() != '' }.size().toString()
                    env.CHANGED_FILES_PATH = changedFilesPath
                    
                    echo "Created changed_files.txt with ${env.CHANGED_FILES_COUNT} files at: ${changedFilesPath}"
                }
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
                            --volumes-from ${env.HOSTNAME} \
                            -v ${env.WORKSPACE}:/mounted_volumes \
                            ${env.CHANGED_FILES_PATH ? '-v ' + env.CHANGED_FILES_PATH + ':/app/files_to_analyze.txt' : ''} \
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
                            ${params.AUTONOMOUS_SCAN ? '--auto' : ''} \
                            ${env.ANALYZE_CHANGED_FILES ? '--files /app/files_to_analyze.txt' : ''} > plexalyzer_output.txt 2>&1
                    """
                    
                    echo "Executing analysis command..."
                    echo "Log file will be saved as: ${logFileName}"
                    
                    if (env.ANALYZE_CHANGED_FILES == 'true') {
                        echo "🔍 Analyzing ${env.CHANGED_FILES_COUNT} changed files only"
                    } else {
                        echo "🔍 Analyzing entire repository"
                    }
                    
                    def exitCode = sh(
                        script: analysisCommand,
                        returnStatus: true
                    )

                    echo "Analysis output: "
                    sh "cat plexalyzer_output.txt"
                    echo "Exit code: ${exitCode}"
                    
                    // Exit code indicates if vulnerabilities were found
                    env.ANALYSIS_EXIT_CODE = exitCode.toString()
                    
                    if (exitCode == 500) {
                        error "Fatal error in security analysis"
                    } else if (exitCode == 0) {
                        echo "✅ Scan completed successfully"
                    } else if (exitCode == 1) {
                        echo "✅ Scan completed successfully"
                    } else if (exitCode == 2) {
                        echo "⚠️  Scan completed successfully"
                        currentBuild.result = 'UNSTABLE'
                    } else {
                        echo "❌ Analysis failed with exit code: ${exitCode}"
                        error "Analysis failed with exit code: ${exitCode}. Check the output for details."
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                // Clean up temporary files
                if (env.ANALYZE_CHANGED_FILES == 'true') {
                    sh "rm -f ${env.CHANGED_FILES_PATH}"
                    echo "Cleaned up temporary files: ${env.CHANGED_FILES_PATH}"
                }
            }
        }
        
        success {
            script {
                def analysisType = env.ANALYZE_CHANGED_FILES == 'true' ? 
                    "incremental analysis (${env.CHANGED_FILES_COUNT} files)" : 
                    "full repository analysis"
                echo "✅ Security ${analysisType} completed successfully, you can check the status of the scan here: https://app.plexicus.ai/repositories"
            }
        }
        
        failure {
            echo "❌ Security analysis failed"
        }
        
        unstable {
            echo "⚠️  Build marked as unstable due to vulnerabilities found"
        }
    }
}