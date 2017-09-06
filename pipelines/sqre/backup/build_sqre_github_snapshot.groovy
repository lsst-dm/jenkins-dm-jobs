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
  def hub_repo = 'lsstsqre/sqre-github-snapshot'

  node('docker') {
    stage('fetch Dockerfile') {
      // `pip download` will fetch all recursive deps, it is more efficient to
      // just fetch the tarball directly
      sh '''
        wget https://pypi.python.org/packages/50/81/30b98d8cf5201b2fbdb2a2391863ee6e9196e74984ee54c986e320d47640/sqre-github-snapshot-0.2.1.tar.gz#md5=6d4d31c33cbc06fa534425a25cc0e81e
        tar -xvf sqre-github-snapshot-0.2.1.tar.gz
        ln -sf sqre-github-snapshot-0.2.1 sqre-github-snapshot
      '''.replaceFirst("\n","").stripIndent()
    }

    stage('pull') {
      docker.image('docker.io/centos:7').pull()
    }

    stage('build') {
      dir('sqre-github-snapshot/docker') {
        image = docker.build("${hub_repo}:${BUILD_TAG}", '--no-cache .')
      }
    }

    stage('push') {
      docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
        image.push()
        image.push('latest')
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
