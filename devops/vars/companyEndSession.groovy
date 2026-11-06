def call(Map cfg = [:]) {
  def slot = cfg.slot ?: '1'
  def standDir = "company-e2e-${slot}"
  def standHost = "e2e${slot}.company.local"
  def services = ['collaborativeeditingservice', 'emailbroker', 'facade', 'filegenerator', 'filemanager',
                  'frontend-application', 'gateway', 'gitmanager', 'landing', 'projectmanager',
                  'symbolrecognizer', 'userprofile']
  def dbServices = ['gateway', 'projectmanager', 'userprofile']

  pipeline {
    agent { label 'docker' }
    options { timestamps(); disableConcurrentBuilds() }
    stages {
      stage('Revert to dev reference') {
        steps {
          withCredentials([usernamePassword(credentialsId: 'gitlab-push', usernameVariable: 'GU', passwordVariable: 'GP')]) {
            script {
              sh """
                rm -rf argocd-git
                git clone -q --depth 1 -b develop "https://\$GU:\$GP@gitlab.example.com/company/argo-cd.git" argocd-git
                cd argocd-git
                ${services.collect { svc ->
                  "T=\$(grep -m1 -E '^[[:space:]]*tag: ' company-helm-chart/values/company-dev/${svc}/values.yaml) && sed -i \"s|^[[:space:]]*tag: .*|\$T|\" company-helm-chart/values/${standDir}/${svc}/values.yaml; F=company-helm-chart/values/${standDir}/${svc}/values.yaml; D=company-helm-chart/values/${standDir}/${svc}/values.reference.yaml; awk -v d=\"\$D\" 'BEGIN{while((getline l < d)>0){if(l ~ /^env:/) ein=1; else if(ein && l != \"\" && l !~ /^ /) ein=0; else if(ein) blk=blk l \"\\n\"}} /^env:/{print; printf \"%s\", blk; skip=1; next} skip && /^ /{next} skip && /^[^ ]/{skip=0} {print}' \$F > \$F.tmp && mv \$F.tmp \$F; if ${dbServices.contains(svc)}; then if grep -q '^deploymentVersion:' \$F; then V=\$(grep -oP '(?<=deploymentVersion: \")[0-9]+' \$F); sed -i \"s|deploymentVersion: \\\"[0-9]*\\\"|deploymentVersion: \\\"\$((V+1))\\\"|\" \$F; else sed -i '1i deploymentVersion: \"1\"' \$F; fi; fi"
                }.join('\n')}
                git config user.email jenkins@example.com
                git config user.name jenkins
                git commit -qam "e2e-${slot}: end session, revert to dev reference" || true
                git push -q origin develop
              """
            }
          }
        }
      }
      stage('Reset data') {
        when { expression { params.RESET_DATA } }
        steps {
          sshagent(credentials: ['ssh-e2e-reset']) {
            sh "ssh -o StrictHostKeyChecking=accept-new e2e-reset@10.10.10.45 slot${slot}"
            sh "ssh -o StrictHostKeyChecking=accept-new e2e-reset@10.10.10.40 slot${slot}"
          }
        }
      }
      stage('Wait gateway') {
        steps {
          timeout(time: 5, unit: 'MINUTES') {
            waitUntil(initialRecurrencePeriod: 15000) {
              script {
                def code = sh(script: "curl -s -o /dev/null -w '%{http_code}' --resolve ${standHost}:30080:10.10.10.107 http://${standHost}:30080/api/swagger/index.html", returnStdout: true).trim()
                return code == '200'
              }
            }
          }
          echo "Стенд e2e-${slot} возвращён к dev-эталону${params.RESET_DATA ? ', данные сброшены ' : ''}. Слот свободен."
        }
      }
    }
  }
}
