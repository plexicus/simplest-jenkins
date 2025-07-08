pipeline {
    agent any
    
    parameters {
        string(name: 'PROJECT_NAME', defaultValue: 'simplest-jenkins', description: 'Project name')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to analyze')
        choice(name: 'OUTPUT_FORMAT', choices: ['pretty', 'json', 'sarif'], description: 'Output format')
        string(name: 'DEFAULT_OWNER', defaultValue: 'plexicus', description: 'Default owner')
        string(name: 'REPOSITORY_ID', defaultValue: '', description: 'Repository ID')
        booleanParam(name: 'AUTONOMOUS_SCAN', defaultValue: false, description: 'Autonomous scan')
        booleanParam(name: 'ONLY_GIT_CHANGES', defaultValue: false, description: 'Only analyze changed files in Git repository')
        booleanParam(name: 'PROGRESS_BAR', defaultValue: true, description: 'Progress bar')
    }
    
    environment {
        DOCKER_IMAGE = 'plexicus/plexalyzer:latest'
        CONFIG_PATH = '/app/config/default_config.yaml'
    }
    
    stages {
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
                        echo "Getting changed files from ${env.WORKSPACE} using Docker..."
                        
                        // First, check if it's a valid git repository without using Docker
                        def gitCheck = sh(
                            script: """
                                git -C "${env.WORKSPACE}" status --porcelain
                            """,
                            returnStatus: true
                        )
                        
                        if (gitCheck != 0) {
                            echo "Not a valid git repository in ${env.WORKSPACE}"
                            echo "Falling back to full repository analysis"
                            env.ANALYZE_CHANGED_FILES = 'false'
                            return
                        }
                        
                        // Get changed files without using Docker
                        changedFiles = sh(
                            script: """
                                git -C "${env.WORKSPACE}" diff --name-only HEAD~1..HEAD
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
                    def changedFilesPath = "${env.WORKSPACE}/.plexalyzer/changed_files.txt"
                    
                    // Create the file on the workspace, .plexalyzer folder
                    sh """
                        mkdir -p ${env.WORKSPACE}/.plexalyzer
                        echo "${changedFilesContent}" > ${env.WORKSPACE}/.plexalyzer/changed_files.txt
                    """
                    
                    // Set environment variable to indicate we're using changed files
                    env.ANALYZE_CHANGED_FILES = 'true'
                    env.CHANGED_FILES_COUNT = changedFiles.split('\n').findAll { it.trim() != '' }.size().toString()
                    
                    echo "Created changed_files.txt with ${env.CHANGED_FILES_COUNT} files at: ${changedFilesPath}"
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
                        docker run --rm \\
                            --volumes-from ${env.HOSTNAME} \\
                            -e MESSAGE_URL=https://api.app.dev.plexicus.ai/receive_plexalyzer_message \\
                            -e PLEXALYZER_TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbGllbnRfaWQiOiI2NWYwNzlmM2VmODk4ZTZhNmJiMzdlNWIiLCJjcmVhdGVkX2F0IjoiMjAyNC0xMS0wOFQxODowMzo0Mi4yODEyODYifQ.xLegYmnZ7Yfvky5D2riNLwyAPkw3RidkKnk2f3vBeoE \\
                            ${DOCKER_IMAGE} \\
                            sh -c 'ln -sf ${env.WORKSPACE} /mounted_volumes && /venvs/plexicus-fastapi/bin/python /app/analyze.py \\
                            --config /mounted_volumes/.plexalyzer/custom_config.yml \\
                            --name "${params.PROJECT_NAME ?: env.JOB_NAME}" \\
                            --output "${params.OUTPUT_FORMAT}" \\
                            --owner "${params.DEFAULT_OWNER}" \\
                            --url "plex://${env.HOSTNAME}${env.WORKSPACE}" \\
                            --branch "${params.BRANCH_NAME ?: env.GIT_BRANCH}" \\
                            --log_file "/mounted_volumes/.plexalyzer/${logFileName}" \\
                            ${params.PROGRESS_BAR ? '' : '--no-progress-bar'} \\
                            ${params.REPOSITORY_ID ? '--repository_id ' + params.REPOSITORY_ID : ''} \\
                            ${params.AUTONOMOUS_SCAN ? '--auto' : ''} \\
                            ${env.ANALYZE_CHANGED_FILES ? '--files /mounted_volumes/.plexalyzer/changed_files.txt' : ''}' 2>&1 | tee plexalyzer_output.txt
                    """
                    
                    echo "Executing analysis command..."
                    echo "Log file will be saved as: ${logFileName}"
                    
                    if (env.ANALYZE_CHANGED_FILES == 'true') {
                        echo "🔍 Analyzing ${env.CHANGED_FILES_COUNT} changed files only"
                    } else {
                        echo "🔍 Analyzing entire repository"
                    }
                    
                    // Try to use ansiColor plugin with direct method detection
                    def exitCode
                    try {
                        // Attempt to use ansiColor directly - if it fails, we'll catch the error
                        echo "🎨 Attempting to use AnsiColor plugin for better output formatting..."
                        ansiColor('xterm') {
                            exitCode = sh(
                                script: analysisCommand,
                                returnStatus: true
                            )
                        }
                        echo "✅ AnsiColor plugin used successfully"
                    } catch (NoSuchMethodError e) {
                        echo "ℹ️  AnsiColor plugin not available - using standard output"
                        exitCode = sh(
                            script: analysisCommand,
                            returnStatus: true
                        )
                    } catch (Exception e) {
                        echo "⚠️  AnsiColor plugin failed, falling back to standard output: ${e.message}"
                        exitCode = sh(
                            script: analysisCommand,
                            returnStatus: true
                        )
                    }
                    
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