pipeline {
    agent any
    
    parameters {
        string(name: 'REPOSITORY_URL', defaultValue: '', description: 'Repository URL to analyze')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to analyze')
        string(name: 'PROJECT_NAME', defaultValue: '', description: 'Project name')
        choice(name: 'OUTPUT_FORMAT', choices: ['json', 'pretty', 'sarif'], description: 'Output format')
        booleanParam(name: 'AUTONOMOUS_SCAN', defaultValue: true, description: 'Enable autonomous scan')
        string(name: 'DEFAULT_OWNER', defaultValue: 'security-team', description: 'Default owner')
    }
    
    environment {
        PLEXALYZER_URL = 'http://plexalyzer-service:8007'
        PLEXALYZER_TOKEN = credentials('plexalyzer-token')
        DOCKER_IMAGE = 'plexalyzer:latest'
        ANALYSIS_TIMEOUT = '30' // minutes
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        
        stage('Start Plexalyzer Service') {
            steps {
                script {
                    // Check if service is already running
                    def serviceRunning = sh(
                        script: "curl -f -s ${PLEXALYZER_URL}/health || echo 'not_running'",
                        returnStdout: true
                    ).trim()
                    
                    if (serviceRunning == 'not_running') {
                        echo "Starting Plexalyzer service..."
                        sh """
                            docker run -d \
                                --name plexalyzer-${BUILD_NUMBER} \
                                --network jenkins \
                                -p 8007:8007 \
                                -v \$(pwd):/mounted_volumes \
                                -v /tmp/analyses:/analyses \
                                ${DOCKER_IMAGE}
                        """
                        
                        // Wait for service to be ready
                        timeout(time: 2, unit: 'MINUTES') {
                            waitUntil {
                                script {
                                    def result = sh(
                                        script: "curl -f -s ${PLEXALYZER_URL}/health",
                                        returnStatus: true
                                    )
                                    return result == 0
                                }
                            }
                        }
                    }
                }
            }
        }
        
        stage('Execute Security Analysis') {
            steps {
                script {
                    def payload = [
                        token: env.PLEXALYZER_TOKEN,
                        files: [],
                        args_data: [
                            scan_path: ".",
                            repository_name: params.PROJECT_NAME ?: env.JOB_NAME,
                            url: params.REPOSITORY_URL ?: env.GIT_URL,
                            branch: params.BRANCH_NAME ?: env.GIT_BRANCH,
                            default_owner: params.DEFAULT_OWNER,
                            output_format: params.OUTPUT_FORMAT
                        ],
                        extra_response_data: [
                            autonomous_scan: params.AUTONOMOUS_SCAN,
                            pr_id: env.CHANGE_ID ?: null,
                            build_number: env.BUILD_NUMBER
                        ]
                    ]
                    
                    // Start analysis
                    def response = sh(
                        script: """
                            curl -X POST ${PLEXALYZER_URL}/analyze \
                                -H "Content-Type: application/json" \
                                -d '${groovy.json.JsonOutput.toJson(payload)}'
                        """,
                        returnStdout: true
                    ).trim()
                    
                    def responseJson = readJSON text: response
                    env.ANALYSIS_REQUEST_ID = responseJson.requestId
                    
                    echo "Analysis started with ID: ${env.ANALYSIS_REQUEST_ID}"
                }
            }
        }
        
        stage('Wait for Analysis Results') {
            steps {
                script {
                    timeout(time: env.ANALYSIS_TIMEOUT.toInteger(), unit: 'MINUTES') {
                        waitUntil {
                            script {
                                def statusCheck = sh(
                                    script: """
                                        curl -s ${PLEXALYZER_URL}/status/${env.ANALYSIS_REQUEST_ID} || echo '{"status": "running"}'
                                    """,
                                    returnStdout: true
                                ).trim()
                                
                                def statusJson = readJSON text: statusCheck
                                
                                if (statusJson.status == 'completed') {
                                    echo "Analysis completed successfully"
                                    return true
                                } else if (statusJson.status == 'failed') {
                                    error "Analysis failed: ${statusJson.error ?: 'Unknown error'}"
                                } else {
                                    echo "Analysis in progress... Status: ${statusJson.status}"
                                    sleep(30)
                                    return false
                                }
                            }
                        }
                    }
                }
            }
        }
        
        stage('Retrieve and Process Results') {
            steps {
                script {
                    // Get results
                    def results = sh(
                        script: """
                            curl -s ${PLEXALYZER_URL}/results/${env.ANALYSIS_REQUEST_ID}
                        """,
                        returnStdout: true
                    ).trim()
                    
                    // Save results
                    writeFile file: 'plexalyzer-results.json', text: results
                    
                    // Process results
                    def resultsJson = readJSON text: results
                    def vulnerabilityCount = resultsJson.findings?.size() ?: 0
                    
                    echo "Analysis completed. Vulnerabilities found: ${vulnerabilityCount}"
                    
                    // Set environment variables for later use
                    env.VULNERABILITY_COUNT = vulnerabilityCount.toString()
                    env.HAS_CRITICAL_VULNERABILITIES = (resultsJson.findings?.any { it.severity == 'CRITICAL' } ?: false).toString()
                    
                    // Archive results
                    archiveArtifacts artifacts: 'plexalyzer-results.json', allowEmptyArchive: false
                }
            }
        }
        
        stage('Generate Reports') {
            steps {
                script {
                    // Generate HTML report
                    def htmlReport = generateHtmlReport(readJSON file: 'plexalyzer-results.json')
                    writeFile file: 'security-report.html', text: htmlReport
                    
                    // Generate SARIF report if requested
                    if (params.OUTPUT_FORMAT == 'sarif') {
                        sh """
                            curl -s ${PLEXALYZER_URL}/results/${env.ANALYSIS_REQUEST_ID}?format=sarif > plexalyzer-results.sarif
                        """
                        archiveArtifacts artifacts: 'plexalyzer-results.sarif', allowEmptyArchive: false
                    }
                    
                    // Publish HTML report
                    publishHTML([
                        allowMissing: false,
                        alwaysLinkToLastBuild: true,
                        keepAll: true,
                        reportDir: '.',
                        reportFiles: 'security-report.html',
                        reportName: 'Plexalyzer Security Report'
                    ])
                }
            }
        }
        
        stage('Quality Gate') {
            steps {
                script {
                    def vulnerabilityCount = env.VULNERABILITY_COUNT.toInteger()
                    def hasCritical = env.HAS_CRITICAL_VULNERABILITIES.toBoolean()
                    
                    // Configure quality thresholds
                    def maxVulnerabilities = 10
                    def allowCritical = false
                    
                    if (hasCritical && !allowCritical) {
                        error "Build failed: Critical vulnerabilities found"
                    }
                    
                    if (vulnerabilityCount > maxVulnerabilities) {
                        unstable "Build marked as unstable: Found ${vulnerabilityCount} vulnerabilities (max allowed: ${maxVulnerabilities})"
                    }
                    
                    echo "Quality Gate passed: ${vulnerabilityCount} vulnerabilities found"
                }
            }
        }
    }
    
    post {
        always {
            script {
                // Clean up Plexalyzer container
                sh """
                    docker rm -f plexalyzer-${BUILD_NUMBER} || true
                """
                
                // Clean up temporary files
                sh """
                    rm -rf /tmp/analyses/${env.ANALYSIS_REQUEST_ID} || true
                """
            }
        }
        
        success {
            echo "Security analysis completed successfully"
            
            // Notify via Slack/Teams if configured
            script {
                if (env.SLACK_WEBHOOK) {
                    def message = """
                        ✅ Security analysis completed for ${params.PROJECT_NAME}
                        📊 Vulnerabilities found: ${env.VULNERABILITY_COUNT}
                        🔗 View report: ${BUILD_URL}Plexalyzer_Security_Report/
                    """
                    
                    sh """
                        curl -X POST -H 'Content-type: application/json' \
                            --data '{"text":"${message}"}' \
                            ${env.SLACK_WEBHOOK}
                    """
                }
            }
        }
        
        failure {
            echo "Security analysis failed"
            
            // Notify failure
            script {
                if (env.SLACK_WEBHOOK) {
                    def message = """
                        ❌ Security analysis failed for ${params.PROJECT_NAME}
                        🔗 View logs: ${BUILD_URL}console
                    """
                    
                    sh """
                        curl -X POST -H 'Content-type: application/json' \
                            --data '{"text":"${message}"}' \
                            ${env.SLACK_WEBHOOK}
                    """
                }
            }
        }
        
        unstable {
            echo "Build marked as unstable due to vulnerabilities"
        }
    }
}

def generateHtmlReport(results) {
    def html = """
    <!DOCTYPE html>
    <html>
    <head>
        <title>Plexalyzer Security Report</title>
        <style>
            body { font-family: Arial, sans-serif; margin: 20px; }
            .header { background: #f4f4f4; padding: 20px; border-radius: 5px; }
            .summary { margin: 20px 0; }
            .finding { border: 1px solid #ddd; margin: 10px 0; padding: 15px; border-radius: 5px; }
            .critical { border-left: 5px solid #d32f2f; }
            .high { border-left: 5px solid #ff5722; }
            .medium { border-left: 5px solid #ff9800; }
            .low { border-left: 5px solid #4caf50; }
            .info { border-left: 5px solid #2196f3; }
        </style>
    </head>
    <body>
        <div class="header">
            <h1>Plexalyzer Security Report</h1>
            <p>Project: ${params.PROJECT_NAME}</p>
            <p>Branch: ${params.BRANCH_NAME}</p>
            <p>Date: ${new Date()}</p>
        </div>
        
        <div class="summary">
            <h2>Summary</h2>
            <p>Total vulnerabilities: ${results.findings?.size() ?: 0}</p>
        </div>
        
        <div class="findings">
            <h2>Vulnerabilities Found</h2>
    """
    
    results.findings?.each { finding ->
        html += """
            <div class="finding ${finding.severity?.toLowerCase()}">
                <h3>${finding.title ?: 'Vulnerability'}</h3>
                <p><strong>Severity:</strong> ${finding.severity}</p>
                <p><strong>File:</strong> ${finding.file}</p>
                <p><strong>Line:</strong> ${finding.line}</p>
                <p><strong>Description:</strong> ${finding.description}</p>
                <p><strong>Tool:</strong> ${finding.tool}</p>
            </div>
        """
    }
    
    html += """
        </div>
    </body>
    </html>
    """
    
    return html
} 