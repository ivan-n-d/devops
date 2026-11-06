def call(Map cfg = [:]) {
  if (!cfg.service) { error('companyService: parameter "service" is required') }
  def reg = "docker-registry.example.com/company/${cfg.service}"
  pipeline {
    agent { label 'docker' }
    options { timestamps(); disableConcurrentBuilds() }
    stages {
      stage('Build image') {
        steps {
          sh "TAG=jenkins-\$(git rev-parse --short=8 HEAD); echo \"building \$TAG\"; docker build -t ${reg}:\$TAG ."
        }
      }
      stage('Push to Nexus') {
        steps {
          withCredentials([usernamePassword(credentialsId: 'nexus-registry', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
            sh 'echo "$REG_PASS" | docker login docker-registry.example.com -u "$REG_USER" --password-stdin'
            sh "TAG=jenkins-\$(git rev-parse --short=8 HEAD); docker push ${reg}:\$TAG"
          }
        }
      }
    }
    post {
      always { sh 'docker logout docker-registry.example.com || true' }
    }
  }
}
