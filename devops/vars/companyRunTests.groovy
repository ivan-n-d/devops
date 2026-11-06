def call(Map cfg = [:]) {
  def slot = cfg.slot ?: '1'
  def stand = "e2e${slot}"
  pipeline {
    agent { label 'docker' }
    options { timestamps(); disableConcurrentBuilds() }
    stages {
      stage('Trigger test-init') {
        steps {
          withCredentials([string(credentialsId: 'test-init-trigger', variable: 'TT'), usernamePassword(credentialsId: 'gitlab-push', usernameVariable: 'GU', passwordVariable: 'GP')]) {
            script {
              def resp = sh(returnStdout: true, script: """
                curl -s -X POST 'https://gitlab.example.com/api/v4/projects/company%2Ftest-init/trigger/pipeline' \
                  --data-urlencode "token=\$TT" --data-urlencode 'ref=master' --data-urlencode 'variables[STAND]=${stand}'
              """).trim()
              def m = resp =~ '"id":([0-9]+)'
              if (!m.find()) { error("trigger failed: ${resp.take(200)}") }
              env.PIPE_ID = m[0][1]
              echo "test-init pipeline #${env.PIPE_ID} запущен (STAND=${stand})"
            }
          }
        }
      }
      stage('Wait tests') {
        steps {
          withCredentials([usernamePassword(credentialsId: 'gitlab-push', usernameVariable: 'GU', passwordVariable: 'GP')]) {
            script {
              def result = ''
              timeout(time: 50, unit: 'MINUTES') {
                waitUntil(initialRecurrencePeriod: 30000) {
                  script {
                    def st = sh(returnStdout: true, script: """
                      curl -s --header "PRIVATE-TOKEN: \$GP" 'https://gitlab.example.com/api/v4/projects/company%2Ftest-init/pipelines/'${env.PIPE_ID} | grep -o '"status":"[a-z_]*"' | head -1
                    """).trim()
                    def s = st.replace('"status":"', '').replace('"', '')
                    if (s == 'success') { result = 'SUCCESS'; return true }
                    if (s == 'failed' || s == 'canceled' || s == 'skipped') { result = s.toUpperCase(); return true }
                    return false
                  }
                }
              }
              if (result != 'SUCCESS') { unstable("test-init: ${result} — упавшие тесты НЕ роняют билд, детали в ReportPortal") }
              else { echo "test-init pipeline #${env.PIPE_ID}: SUCCESS (STAND=${stand})" }
            }
          }
        }
      }
    }
  }
}
