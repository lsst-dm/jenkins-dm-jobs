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
    util = load 'pipelines/lib/util.groovy'
  }
}

notify.wrap {
  def image = null
  util.nodeWrap('docker') {
    stage('pull centos:7') {
      docker.image('centos:7').pull()
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
      echo "path outside of container is: ${pwd()}"
      image.inside { c ->
        echo "path inside of container is: ${pwd()}"
        sh 'cat /arg.txt'
        dir('test_dir') {
          echo "path inside of container dir() is: ${pwd()}"
        }
      }
    }
  }
} // notify.wrap
