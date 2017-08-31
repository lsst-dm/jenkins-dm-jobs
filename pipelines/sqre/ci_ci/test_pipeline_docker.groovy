def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
}

try {
  notify.started()

  def image = null
  node('docker') {
    stage('pull centos:7') {
      docker.image('docker.io/centos:7').pull()
    }

    stage('build Dockerfile') {
      def dockerfile = '''
        FROM centos:7

        ARG text

        RUN echo $text >> /arg.txt
      '''.replaceFirst("\n","").stripIndent()

      writeFile(file: 'Dockerfile', text: dockerfile)

      // if any args are supplied, you become responsible for setting the path
      // to the Dockerfile
      image = docker.build('test:mytag', '--build-arg text="this is a test" .')
    }

    stage('run image') {
      image.inside { c ->
        sh 'cat /arg.txt'
      }
    }
  }
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}
