# Vulnerable Web Application

A deliberately vulnerable Java Spring Boot web application designed for security testing and educational purposes. This application contains various security vulnerabilities including SQL Injection, Cross-Site Scripting (XSS), and information disclosure.

## ⚠️ Security Warning

**This application is intentionally vulnerable and should NEVER be deployed in a production environment or on systems connected to the internet without proper isolation.**

## Vulnerabilities Included

### SQL Injection
- Login form with vulnerable SQL queries
- Search functionality with SQL injection points
- Database error information disclosure

### Cross-Site Scripting (XSS)
- Reflected XSS in error messages
- Stored XSS potential in user input
- URL parameter XSS vulnerabilities

### Information Disclosure
- Exposed database console (H2)
- Password disclosure in admin panel
- Detailed error messages

## Architecture

The project includes:
- **Java Spring Boot Application**: Main vulnerable web application
- **Jenkins Pipeline**: CI/CD pipeline with Plexalyzer integration
- **GitHub Actions**: Triggers Jenkins builds
- **Docker Containerization**: Complete containerized deployment
- **Hetzner Deployment**: Production-ready Docker Compose setup

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Docker Compose
- Jenkins (for CI/CD)
- GitHub repository with Actions enabled

## Quick Start

### Local Development

```bash
# Clone the repository
git clone <repository-url>
cd simplest-jenkins

# Run with Maven
mvn spring-boot:run

# Or with Docker
docker-compose up --build
```

Access the application at: http://localhost:8080

### Sample Credentials

- **Admin**: admin / admin123
- **User**: user1 / password
- **Test**: test / test

## Testing Vulnerabilities

### SQL Injection Examples

**Login Form:**
```
Username: admin' OR '1'='1'--
Password: anything
```

**Search Form:**
```
Search: ' UNION SELECT id,username,password,email,role FROM users--
```

### XSS Examples

**Profile Page:**
```
http://localhost:8080/profile?message=<script>alert('XSS')</script>
```

**Admin Panel:**
```
http://localhost:8080/admin?debug=<img src=x onerror=alert('XSS')>
```

## CI/CD Pipeline

### GitHub Actions Setup

1. Configure secrets in your GitHub repository:
   - `JENKINS_URL`: Your Jenkins server URL
   - `JENKINS_USER`: Jenkins username
   - `JENKINS_TOKEN`: Jenkins API token

2. Push to `main` or `develop` branch to trigger the pipeline

### Jenkins Configuration

1. Install required plugins:
   - Docker Pipeline
   - Git
   - Maven Integration

2. Configure tools:
   - Maven 3.9.0
   - JDK 17

3. Set up credentials:
   - Docker registry credentials
   - SSH key for Hetzner deployment

### Plexalyzer Integration

The pipeline includes placeholder comments for Plexalyzer integration:

```groovy
// Call plexalyzer
// Configure plexalyzer with proper credentials and scan parameters
```

**Manual Configuration Required:**
1. Set up Plexalyzer credentials in Jenkins
2. Configure scan parameters for your environment
3. Replace placeholder comments with actual Plexalyzer commands

## Deployment

### Staging Environment

```bash
docker-compose -f docker-compose.staging.yml up -d
```

### Production on Hetzner

1. Set up your Hetzner server
2. Configure SSH access in Jenkins
3. Deploy using the production pipeline or manually:

```bash
# On Hetzner server
cd /opt/vulnerable-webapp
docker-compose -f docker-compose.prod.yml up -d
```

## Project Structure

```
├── src/main/java/com/vulnerable/webapp/
│   ├── VulnerableWebApplication.java
│   ├── controller/UserController.java
│   ├── model/User.java
│   └── repository/UserRepository.java
├── src/main/resources/
│   ├── templates/          # Thymeleaf templates
│   ├── application.properties
│   └── data.sql           # Sample data
├── .github/workflows/ci.yml
├── Jenkinsfile
├── docker-compose.yml
├── docker-compose.staging.yml
├── docker-compose.prod.yml
├── Dockerfile
├── nginx.conf
└── README.md
```

## Security Testing

This application is perfect for testing:
- **SAST Tools**: Static Application Security Testing
- **DAST Tools**: Dynamic Application Security Testing
- **Security Scanners**: Vulnerability assessment tools
- **Penetration Testing**: Manual security testing

## Database Access

H2 Console is available at: http://localhost:8080/h2-console

**Connection Details:**
- JDBC URL: `jdbc:h2:mem:vulnerable_db`
- Username: `sa`
- Password: `password`

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | - |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m` |
| `DB_PASSWORD` | Database password (prod) | - |

## Contributing

This is an educational project. When contributing:
1. Ensure vulnerabilities remain intentional and documented
2. Add comments explaining security issues
3. Update documentation for new vulnerabilities

## Disclaimer

This application is created for educational and testing purposes only. The developers are not responsible for any misuse of this application. Always ensure proper authorization before testing on any systems.

## License

This project is licensed under the MIT License - see the LICENSE file for details. 