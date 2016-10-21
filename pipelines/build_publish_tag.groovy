def notify = null
node {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
  notify = load 'pipelines/lib/notify.groovy'
}

try {
  notify.started()

  // eups doesn't like dots in tags, convert to underscores
  def EUPS_TAG = GIT_TAG.tr('.-', '_')

  echo "branch: ${BRANCH}"
  echo "product: ${PRODUCT}"
  echo "skip demo: ${SKIP_DEMO}"
  echo "skip docs: ${SKIP_DOCS}"
  echo "[git] tag: ${GIT_TAG}"
  echo "[eups] tag: ${EUPS_TAG}"


  stage 'build' {
    def result = build job: 'run-rebuild',
        parameters: [
          string(name: 'BRANCH', value: BRANCH),
          string(name: 'PRODUCT', value: PRODUCT),
          booleanParam(name: 'SKIP_DEMO', value: SKIP_DEMO.toBoolean()),
          booleanParam(name: 'SKIP_DOCS', value: SKIP_DOCS.toBoolean())
        ],
        wait: true
    def rebuildId = result.id
  }

  stage 'parse bNNNN' {
    node {
      step ([$class: 'CopyArtifact',
            projectName: 'run-rebuild',
            filter: 'build/manifest.txt',
            selector: [$class: 'SpecificBuildSelector', buildNumber: rebuildId]
            ]);

      def manifest = readFile 'build/manifest.txt'
      def bx = bxxxx(manifest)

      echo "parsed bxxxx: ${bx}"
    }
  }

  stage 'eups publish [tag]' {
    build job: 'run-publish',
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: bx),
        string(name: 'TAG', value: EUPS_TAG),
        string(name: 'PRODUCTS', value: PRODUCT)
      ]
  }

  stage 'eups publish [w_latest]' {
    build job: 'run-publish',
      parameters: [
        string(name: 'EUPSPKG_SOURCE', value: 'git'),
        string(name: 'BUILD_ID', value: bx),
        string(name: 'TAG', value: 'w_latest'),
        string(name: 'PRODUCTS', value: PRODUCT)
      ]
  }

  stage 'git tag' {
    build job: 'release/tag-git-repos',
      parameters: [
        string(name: 'BUILD_ID', value: bx),
        string(name: 'GIT_TAG', value: GIT_TAG),
        booleanParam(name: 'DRY_RUN', value: false)
      ]
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

@NonCPS

def bxxxx(manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}
