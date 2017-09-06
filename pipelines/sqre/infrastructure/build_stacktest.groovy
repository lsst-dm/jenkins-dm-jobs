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

  def tag = params.TAG
  def baseImage = 'lsstsqre/centos:7-stackbase'
  def hubRepo = 'lsstsqre/stacktest'
  def slug = "${hubRepo}:7-stack-lsst_distrib-${params.TAG}"

  if (!params.TAG) {
    error 'TAG parameter is required'
  }

  node('docker') {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/docker-tarballs',
        branch: 'master'
      ])
    }

    stage('pull') {
      def image = docker.image(baseImage)
      image.pull()
    }

    stage('build') {
      util.shColor "docker build --build-arg TAG=\"${tag}\" -t \"${slug}\" ."
    }

    stage('push') {
      docker.withRegistry(
        'https://index.docker.io/v1/',
        'dockerhub-sqreadmin'
      ) {
        util.shColor "docker push \"${slug}\""
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
