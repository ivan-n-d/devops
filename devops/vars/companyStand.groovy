def call(Map cfg = [:]) {
  if (!cfg.service || !cfg.branch) { error('companyStand: service и branch обязательны') }
  def slot = cfg.slot ?: '1'
  def svc = cfg.service
  def reg = "docker-registry.example.com/company/${svc}"
  def standHost = "e2e${slot}.company.local"
  def standDir = "company-e2e-${slot}"
  def standAddressServices = ['projectmanager', 'gitmanager', 'filegenerator', 'facade', 'gateway']
  def buildStandAddress = cfg.standAddress ?: 'dev.company-k8s.local:30080'
  def featureTag = ''

  pipeline {
    agent { label 'docker' }
    options { timestamps(); disableConcurrentBuilds() }
    stages {
      stage('Checkout branch') {
        steps {
          git branch: cfg.branch, credentialsId: 'gitlab-repo', url: cfg.repo
          script { featureTag = "feature-" + sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim() }
        }
      }
      stage('Build image') {
        steps {
          script {
            def buildArgs = standAddressServices.contains(svc) ? "--build-arg STAND_ADDRESS=${buildStandAddress}" : ''
            sh "DOCKER_BUILDKIT=1 docker build ${buildArgs} -t ${reg}:${featureTag} ."
          }
        }
      }
      stage('Push to Nexus') {
        steps {
          withCredentials([usernamePassword(credentialsId: 'nexus-registry', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
            sh 'echo "$REG_PASS" | docker login docker-registry.example.com -u "$REG_USER" --password-stdin'
            sh "docker push ${reg}:${featureTag}"
          }
        }
      }
      stage('Bump slot values') {
        steps {
          withCredentials([usernamePassword(credentialsId: 'gitlab-push', usernameVariable: 'GU', passwordVariable: 'GP')]) {
            script {
              def attempt = 0
              while (true) {
                def rc = sh(returnStatus: true, script: """
                  rm -rf argocd-git
                  git clone -q --depth 1 -b develop "https://\$GU:\$GP@gitlab.example.com/company/argo-cd.git" argocd-git || exit 1
                  cd argocd-git
                  F="company-helm-chart/values/${standDir}/${svc}/values.yaml"
                  sed -i "s|^[[:space:]]*tag: .*|  tag: ${featureTag}|" "\$F"
                  grep -q "tag: ${featureTag}" "\$F" || { echo "TAG NOT SET in \$F"; exit 1; }
                  ${params.RESET_DATA ? '''
                  for s in gateway projectmanager userprofile; do
                    FS="company-helm-chart/values/''' + standDir + '''/\$s/values.yaml"
                    if grep -q '^deploymentVersion:' \$FS; then V=\$(grep -oP '(?<=deploymentVersion: ")[0-9]+' \$FS); sed -i "s|deploymentVersion: \\"[0-9]*\\"|deploymentVersion: \\"\$((V+1))\\"" \$FS; else sed -i '1i deploymentVersion: "1"' \$FS; fi
                  done
                  ''' : ':'}
                  ${injectEnv(svc, standDir, params.EXTRA_ENV)}
                  git config user.email jenkins@example.com
                  git config user.name jenkins
                  git commit -qam "e2e-${slot}: ${svc} ${cfg.branch} -> ${featureTag}" || true
                  git push -q origin develop
                """)
                if (rc == 0) break
                attempt++
                if (attempt >= 3) { error('Не удалось запушить в argo-cd (конфликт?). Повторите запуск.') }
                sleep time: 5, unit: 'SECONDS'
              }
            }
          }
        }
      }
      stage('Reset data') {
        when { expression { params.RESET_DATA } }
        steps {
          sshagent(credentials: ['ssh-e2e-reset']) {
            sh "ssh -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 e2e-reset@10.10.10.45 slot${slot}"
            sh "ssh -o StrictHostKeyChecking=accept-new e2e-reset@10.10.10.40 slot${slot}"
          }
        }
      }
      stage('Wait rollout') {
        steps {
          script {
            def path = (svc == 'frontend-application') ? '/' : '/health'
            def host = (svc in ['gateway', 'frontend-application']) ? standHost : "${svc}.${standHost}"
            timeout(time: 5, unit: 'MINUTES') {
              waitUntil(initialRecurrencePeriod: 15000) {
                script {
                  def code = sh(script: "curl -s -o /dev/null -w '%{http_code}' --resolve ${host}:30080:10.10.10.107 http://${host}:30080${path}", returnStdout: true).trim()
                  return code == '200'
                }
              }
            }
            echo """Стенд e2e-${slot}: ${svc} -> ${featureTag} (ветка ${cfg.branch})
Шлюз:       http://${standHost}:30080
Сваггер GW: http://${standHost}:30080/api/swagger/index.html
Сервис:     http://${host}:30080${path}"""
          }
        }
      }
    }
    post {
      always { sh 'docker logout docker-registry.example.com || true' }
    }
  }
}

private String injectEnv(String svc, String standDir, String extra) {
  if (!extra?.trim()) { return '' }
  def f = "company-helm-chart/values/${standDir}/${svc}/values.yaml"
  return extra.split(',').collect { kv ->
    def parts = kv.trim().split('=', 2)
    def k = parts[0].trim()
    def v = parts.length > 1 ? parts[1].trim() : ''
    "grep -q '^  ${k}:' ${f} && sed -i 's|^  ${k}: .*|  ${k}: \"${v}\"|' ${f} || sed -i '/^env:/a\\\\  ${k}: \"${v}\"' ${f}"
  }.join('\n')
}
