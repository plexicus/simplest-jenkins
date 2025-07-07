pipeline {
    agent any
    
    parameters {
        string(name: 'PROJECT_NAME', defaultValue: '', description: 'Project name')
        string(name: 'REPOSITORY_URL', defaultValue: '', description: 'Repository URL')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to analyze')
        choice(name: 'OUTPUT_FORMAT', choices: ['json', 'pretty', 'sarif'], description: 'Output format')
        string(name: 'DEFAULT_OWNER', defaultValue: 'security-team', description: 'Default owner')
        booleanParam(name: 'AUTONOMOUS_SCAN', defaultValue: true, description: 'Autonomous scan')
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
                    echo "Downloading Plexalyzer Docker image from Docker Hub..."
                    sh "docker pull ${DOCKER_IMAGE}"
                    echo "✅ Docker image downloaded successfully"
                }
            }
        }
        
        stage('Prepare Analysis') {
            steps {
                script {
                    // Create results directory
                    sh 'mkdir -p analysis-results'
                    
                    // Create custom configuration file if needed
                    def customConfig = """
message_url: "http://localhost:6000/receive_plexalyzer_message"
plexalyzer_token: "default_token"
default_owner: "${params.DEFAULT_OWNER}"
excluded_tools:
  - syft
  - trivy-sbom
                    """
                    
                    writeFile file: 'custom_config.yaml', text: customConfig
                }
            }
        }
        
        stage('Security Analysis') {
            steps {
                script {
                    def analysisCommand = """
                        docker run --rm \
                            -v \$(pwd):/app/code \
                            -v \$(pwd)/analysis-results:/analyses \
                            -v \$(pwd)/custom_config.yaml:/app/config/custom_config.yaml \
                            -w /app/code \
                            ${DOCKER_IMAGE} \
                            /venvs/plexicus-fastapi/bin/python /app/analyze.py \
                            --name "${params.PROJECT_NAME ?: env.JOB_NAME}" \
                            --url "${params.REPOSITORY_URL ?: env.GIT_URL}" \
                            --branch "${params.BRANCH_NAME ?: env.GIT_BRANCH}" \
                            --owner "${params.DEFAULT_OWNER}" \
                            --output "${params.OUTPUT_FORMAT}" \
                            --config "/app/config/custom_config.yaml" \
                            --log_file "/analyses/plexalyzer.log" \
                            ${params.AUTONOMOUS_SCAN ? '--auto' : ''} \
                            --no-progress-bar
                    """
                    
                    // Execute analysis and capture exit code
                    def exitCode = sh(
                        script: analysisCommand,
                        returnStatus: true
                    )
                    
                    // Exit code indicates if vulnerabilities were found
                    env.ANALYSIS_EXIT_CODE = exitCode.toString()
                    
                    if (exitCode == 500) {
                        error "Fatal error in security analysis"
                    } else if (exitCode == 1) {
                        echo "⚠️  Vulnerabilities found in code"
                        env.VULNERABILITIES_FOUND = "true"
                    } else {
                        echo "✅ No vulnerabilities found"
                        env.VULNERABILITIES_FOUND = "false"
                    }
                }
            }
        }
        
        stage('Process Results') {
            steps {
                script {
                    // Archive logs
                    if (fileExists('analysis-results/plexalyzer.log')) {
                        archiveArtifacts artifacts: 'analysis-results/plexalyzer.log', allowEmptyArchive: true
                    }
                    
                    // Find and archive results
                    def resultFiles = sh(
                        script: 'find analysis-results -name "*.json" -o -name "*.sarif" 2>/dev/null || echo ""',
                        returnStdout: true
                    ).trim()
                    
                    if (resultFiles) {
                        echo "Archiving results: ${resultFiles}"
                        archiveArtifacts artifacts: 'analysis-results/**/*', allowEmptyArchive: true
                        
                        // Generate results summary
                        generateResultsSummary()
                    }
                }
            }
        }
        
        stage('Quality Gate') {
            steps {
                script {
                    if (env.VULNERABILITIES_FOUND == "true") {
                        // Configure quality policy
                        def allowVulnerabilities = true  // Change according to policy
                        
                        if (allowVulnerabilities) {
                            unstable "Build marked as unstable: Security vulnerabilities found"
                        } else {
                            error "Build failed: Security vulnerabilities found"
                        }
                    } else {
                        echo "✅ Quality Gate passed: No vulnerabilities found"
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                // Clean up temporary files
                sh 'rm -f custom_config.yaml'
                
                // Publish test results if they exist
                if (fileExists('analysis-results')) {
                    publishHTML([
                        allowMissing: true,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: 'analysis-results',
                        reportFiles: '*.html',
                        reportName: 'Plexalyzer Security Report'
                    ])
                }
            }
        }
        
        success {
            echo "✅ Security analysis completed successfully"
            
            script {
                // Success notification
                if (env.VULNERABILITIES_FOUND == "false") {
                    echo "🎉 Excellent! No security vulnerabilities found"
                } else {
                    echo "⚠️  Analysis completed but vulnerabilities were found"
                }
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

def generateResultsSummary() {
    def summary = """
    <h2>Security Analysis Summary</h2>
    <p><strong>Project:</strong> ${params.PROJECT_NAME ?: env.JOB_NAME}</p>
    <p><strong>Branch:</strong> ${params.BRANCH_NAME ?: env.GIT_BRANCH}</p>
    <p><strong>Date:</strong> ${new Date()}</p>
    <p><strong>Build:</strong> ${env.BUILD_NUMBER}</p>
    <p><strong>Status:</strong> ${env.VULNERABILITIES_FOUND == 'true' ? 'Vulnerabilities found' : 'No vulnerabilities'}</p>
    
    <h3>Result Files</h3>
    <ul>
    """
    
    def files = sh(
        script: 'find analysis-results -type f 2>/dev/null || echo ""',
        returnStdout: true
    ).trim().split('\n')
    
    files.each { file ->
        if (file.trim()) {
            summary += "<li>${file}</li>"
        }
    }
    
    summary += """
    </ul>
    
    <p><em>Check archived artifacts for more details.</em></p>
    """
    
    writeFile file: 'analysis-results/summary.html', text: summary
} 