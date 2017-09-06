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
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  node('docker') {
    // XXX we need to do some mangling of the PRODUCTS param for the docker tag
    // if more than one product is listed
    stage('pull') {
      //docker.image('lsstsqre/centos:6-newinstall').pull()
      docker.image('lsstsqre/centos:7-newinstall').pull()
    }

    stage('build') {
      git([
        url: 'https://github.com/lsst-sqre/packer-newinstall.git',
        branch: 'master'
      ])

      def make = '''
        gem install --no-ri --no-rdoc bundler

        ./build-docker build
      '''.stripIndent()

      rvm(make)
    }

    if (PUBLISH) {
      stage('push') {
        docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
          //docker.image("lsstsqre/centos:6-stack-${PRODUCTS}-${TAG}").push()
          docker.image("lsstsqre/centos:7-stack-${PRODUCTS}-${TAG}").push()
        }
      }
    }
  } // node
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

def rvm(String commands) {
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm install 2.2 > /dev/null 2>&1'"
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm use 2.2 && ${commands}'"
}
