# Jenkins Configuration for Plexalyzer

## Prerequisites

1. **Jenkins with Docker**: Jenkins must have Docker access
2. **Required Plugins**:
   - Pipeline Plugin
   - Docker Pipeline Plugin
   - HTML Publisher Plugin
   - Credentials Plugin

## Initial Setup

### 1. Jenkins Credentials

Go to `Manage Jenkins` → `Manage Credentials` and add:

```
ID: plexalyzer-token
Type: Secret text
Secret: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjbGllbnRfaWQiOiI2NWYwNzlmM2VmODk4ZTZhNmJiMzdlNWIiLCJjcmVhdGVkX2F0IjoiMjAyNC0xMC0yOFQxMzo0Mzo0NS44MjQ1MDgifQ.FFpIrAD6av71xzInPBchMzTZwWOOfUvN4h9OGCfRLGM
```

### 2. Global Environment Variables

In `Manage Jenkins` → `Configure System` → `Global Properties`:

```
PLEXALYZER_IMAGE=plexalyzer:latest
PLEXALYZER_SERVICE_URL=http://plexalyzer-service:8007
SLACK_WEBHOOK=https://hooks.slack.com/services/YOUR/WEBHOOK/URL
```

### 3. Build Docker Image

```bash
# On Jenkins server
cd /path/to/plexalyzer
docker build -t plexalyzer:latest .
```

## Implementation Methods

### Method 1: API (Recommended)

**Advantages:**
- ✅ Asynchronous and scalable
- ✅ Better resource management
- ✅ Multiple simultaneous analyses
- ✅ Advanced monitoring

**Disadvantages:**
- ❌ More complex to configure
- ❌ Requires persistent service

**Usage:** `jenkins-plexalyzer-pipeline.groovy`

### Method 2: Direct CLI

**Advantages:**
- ✅ Simpler to configure
- ✅ No persistent service required
- ✅ Direct execution

**Disadvantages:**
- ❌ Blocks executor during analysis
- ❌ Less scalable
- ❌ Higher resource consumption

**Usage:** `jenkins-plexalyzer-cli.groovy`

## Pipeline Configuration

### 1. Create New Pipeline Job

1. `New Item` → `Pipeline`
2. Name: `Security-Analysis-Plexalyzer`
3. In `Pipeline` → `Definition`: `Pipeline script from SCM`
4. Configure Git repository
5. `Script Path`: `jenkins-plexalyzer-pipeline.groovy`

### 2. Configure Parameters

Parameters are automatically configured on first build:

- **REPOSITORY_URL**: Repository URL to analyze
- **BRANCH_NAME**: Branch (default: main)
- **PROJECT_NAME**: Project name
- **OUTPUT_FORMAT**: json/pretty/sarif
- **AUTONOMOUS_SCAN**: true/false
- **DEFAULT_OWNER**: Vulnerability owner

### 3. Configure Triggers

For automatic analysis:

```groovy
triggers {
    // Nightly analysis
    cron('H 2 * * *')
    
    // On every push to main
    githubPush()
    
    // On Pull Requests
    pullRequest {
        cron('H/5 * * * *')
    }
}
```

## Service Configuration

### Docker Compose for Persistent Service

```yaml
version: '3.8'
services:
  plexalyzer:
    image: plexalyzer:latest
    ports:
      - "8007:8007"
    volumes:
      - /var/jenkins_home/analyses:/analyses
      - /var/jenkins_home/workspace:/mounted_volumes
    environment:
      - ENVIRONMENT=prod
    restart: unless-stopped
    networks:
      - jenkins

networks:
  jenkins:
    external: true
```

### Systemd Service (Alternative)

```ini
[Unit]
Description=Plexalyzer Security Analysis Service
After=docker.service
Requires=docker.service

[Service]
Type=simple
ExecStart=/usr/bin/docker run --rm \
    --name plexalyzer-service \
    -p 8007:8007 \
    -v /var/jenkins_home/analyses:/analyses \
    -v /var/jenkins_home/workspace:/mounted_volumes \
    plexalyzer:latest
ExecStop=/usr/bin/docker stop plexalyzer-service
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## Quality Gates Integration

### SonarQube Integration

```groovy
stage('SonarQube Analysis') {
    steps {
        script {
            // Convert results to SonarQube format
            sh """
                python3 -c "
import json
import xml.etree.ElementTree as ET

with open('plexalyzer-results.json', 'r') as f:
    results = json.load(f)

# Convert to SonarQube External Issues format
# ... conversion code ...
"
            """
            
            // Execute SonarQube
            withSonarQubeEnv('SonarQube') {
                sh 'sonar-scanner -Dsonar.externalIssuesReportPaths=external-issues.json'
            }
        }
    }
}
```

### Slack Notifications

```groovy
post {
    always {
        script {
            def color = 'good'
            def message = "✅ Security Analysis completed"
            
            if (env.VULNERABILITIES_FOUND == 'true') {
                color = 'warning'
                message = "⚠️ Security vulnerabilities found"
            }
            
            slackSend(
                channel: '#security',
                color: color,
                message: """
                    ${message}
                    Project: ${params.PROJECT_NAME}
                    Build: ${env.BUILD_URL}
                    Report: ${env.BUILD_URL}Plexalyzer_Security_Report/
                """
            )
        }
    }
}
```

## Troubleshooting

### Common Issues

1. **Docker permission denied**:
   ```bash
   sudo usermod -aG docker jenkins
   sudo systemctl restart jenkins
   ```

2. **Port 8007 occupied**:
   ```bash
   sudo lsof -i :8007
   sudo kill -9 <PID>
   ```

3. **Insufficient memory**:
   ```bash
   # Increase Docker memory
   docker run --memory=4g --memory-swap=8g ...
   ```

### Logs and Debugging

```bash
# Container logs
docker logs plexalyzer-service

# Jenkins logs
tail -f /var/log/jenkins/jenkins.log

# Verify service
curl -f http://localhost:8007/health
```

## Best Practices

1. **Use API method for production**
2. **Configure Docker resource limits**
3. **Implement log rotation**
4. **Configure alerts for failures**
5. **Use incremental analysis when possible**
6. **Archive historical results**
7. **Configure appropriate timeouts**

## Usage Example

```bash
# Manual trigger
curl -X POST http://jenkins.company.com/job/Security-Analysis-Plexalyzer/buildWithParameters \
  -d "REPOSITORY_URL=https://github.com/company/app.git" \
  -d "BRANCH_NAME=develop" \
  -d "PROJECT_NAME=MyApp" \
  -d "OUTPUT_FORMAT=json"
``` 