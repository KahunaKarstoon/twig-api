@Library('buildit') _
pipeline {
  agent any
  options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
    disableConcurrentBuilds()
    skipStagesAfterUnstable()
  }
  stages {
    stage('Backup Twig') {
      steps {
        sh "./backup-db.sh"
      }
    }
  }
  post {
    failure {
      script {
        slackNotify(
          "Twig CouchDB Backup Failed",
          "${env.BUILD_URL}",
          "danger",
          "http://i296.photobucket.com/albums/mm200/kingzain/the_eye_of_sauron_by_stirzocular-d86f0oo_zpslnqbwhv2.png",
          slackChannel
        )
      }
    }
    unstable {
      script {
        slackNotify(
          "Twig CouchDB Backup Failed",
          "${env.BUILD_URL}",
          "danger",
          "http://i296.photobucket.com/albums/mm200/kingzain/the_eye_of_sauron_by_stirzocular-d86f0oo_zpslnqbwhv2.png",
          slackChannel
        )
      }
    }
  }
}
