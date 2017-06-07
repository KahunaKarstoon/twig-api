@Library('buildit')
def LOADED = true

node {
  withEnv(["PATH+NODE=${tool name: 'lts/boron', type: 'jenkins.plugins.nodejs.tools.NodeJSInstallation'}/bin"]) {
    currentBuild.result = "SUCCESS"

    try {
      stage("Set Up") {
        checkout scm
        // clean the workspace
        sh "git clean -ffdx"

        sendNotifications = !env.DEV_MODE
        ad_ip_address = sh(script: "dig +short corp.${env.RIG_DOMAIN} | head -1", returnStdout: true).trim()

        if (env.USE_GLOBAL_LIB) {
          ecrInst = new ecr()
          gitInst = new git()
          npmInst = new npm()
          shellInst = new shell()
          slackInst = new slack()
          convoxInst = new convox()
          templateInst = new template()
        } else {
          sh "curl -L https://dl.bintray.com/buildit/maven/jenkins-pipeline-libraries-${env.PIPELINE_LIBS_VERSION}.zip -o lib.zip && echo 'A' | unzip -o lib.zip"
          ecrInst = load "lib/ecr.groovy"
          gitInst = load "lib/git.groovy"
          npmInst = load "lib/npm.groovy"
          shellInst = load "lib/shell.groovy"
          slackInst = load "lib/slack.groovy"
          convoxInst = load "lib/convox.groovy"
          templateInst = load "lib/template.groovy"
        }
        registryBase = "006393696278.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
        registry = "https://${registryBase}"
        appName = "twig-api"
        appUrl = "http://staging.twig-api.riglet"
        slackChannel = "twig"
        gitUrl = "https://github.com/buildit/twig-api"
      }

      stage("Checkout") {
        // global for exception handling
        shortCommitHash = gitInst.getShortCommit()
        commitMessage = gitInst.getCommitMessage()
        version = npmInst.getVersion()
      }

      stage("Install") {
        sh "npm install"
      }

      stage("Test") {
        try {
          sh "npm run test:ci"
        }
        finally {
          junit 'reports/test-results.xml'
        }
        publishHTML(target: [reportDir: 'reports', reportFiles: 'index.html', reportName: 'Coverage Results'])
      }

      stage("Analysis") {
        sh "npm run lint"
        sh "npm run validate"
        sh "npm run security"
        sh "/usr/local/bin/sonar-scanner -Dsonar.projectVersion=${version}"
      }

      stage("Package") {
        sh "npm shrinkwrap"
      }

      stage("Docker Image Build") {
        tag = "${version}-${shortCommitHash}-${env.BUILD_NUMBER}"
        image = docker.build("${appName}:${tag}", '.')
        ecrInst.authenticate(env.AWS_REGION)
      }

      stage("Docker Push") {
        docker.withRegistry(registry) {
          image.push("${tag}")
        }
      }

      stage("Deploy To AWS") {
        def tmpFile = UUID.randomUUID().toString() + ".tmp"
        def ymlData = templateInst.transform(readFile("docker-compose.yml.template"),
          [tag: tag, registryBase: registryBase, ad_ip_address: ad_ip_address])
        writeFile(file: tmpFile, text: ymlData)

        sh "convox login ${env.CONVOX_RACKNAME} --password ${env.CONVOX_PASSWORD}"
        sh "convox deploy --app ${appName}-staging --description '${tag}' --file ${tmpFile} --wait"
        // wait until the app is deployed
        convoxInst.waitUntilDeployed("${appName}-staging")
        convoxInst.ensureSecurityGroupSet("${appName}-staging", env.CONVOX_SECURITYGROUP)
        convoxInst.ensureCertificateSet("${appName}-staging", "node", 443, "acm-b53eb2937b23")
        convoxInst.ensureParameterSet("${appName}-staging", "Internal", "No")
      }

      stage("Run Functional Tests") {
        // run integration tests
        try {
          sh "URL=${appUrl} npm run test:e2e:ci"
        }
        finally {
          junit 'reports/e2e-test-results.xml'
        }
      }

      stage("Promote Build to latest") {
        docker.withRegistry(registry) {
          image.push("latest")
        }
      }
    }
    catch (err) {
      currentBuild.result = "FAILURE"
      throw err
    }
    finally {
      if (sendNotifications) {
        if (currentBuild.result == null || currentBuild.result == "SUCCESS") {
          slackInst.notify("Deployed to Staging", "Commit '<${gitUrl}/commits/${shortCommitHash}|${shortCommitHash}>' has been deployed to <${appUrl}|${appUrl}>\n\n${commitMessage}", "good", "http://i296.photobucket.com/albums/mm200/kingzain/the_eye_of_sauron_by_stirzocular-d86f0oo_zpslnqbwhv2.png", slackChannel)
        } else {
          slackInst.notify("Error while deploying to Staging", "Commit '<${gitUrl}/commits/${shortCommitHash}|${shortCommitHash}>' failed to deploy to <${appUrl}|${appUrl}>.", "danger", "http://i296.photobucket.com/albums/mm200/kingzain/the_eye_of_sauron_by_stirzocular-d86f0oo_zpslnqbwhv2.png", slackChannel)
        }
      }
    }
  }
}
