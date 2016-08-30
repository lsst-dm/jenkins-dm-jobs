def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    git([
      url: 'https://github.com/lsst-sqre/jenkins-dm-jobs.git',
      branch: 'master'
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
}

try {
  notify.started()

  def jobParams = [
    'BRANCH',
    'PRODUCT',
    'SKIP_DEMO',
    'NO_FETCH',
    'python',
  ]

  for (p in jobParams) {
    injectParam(p)
  }

  stage 'build'

  node('osx') {
    // use different workspace dirs for python 2/3 to avoid residual state
    // conflicts
    dir(python) {
      try {
        dir('lsstsw') {
          git([
            url: 'https://github.com/lsst/lsstsw.git',
            branch: 'master'
          ])
        }

        dir('buildbot-scripts') {
          git([
            url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
            branch: 'master'
          ])
        }

        withEnv(["WORKSPACE=${pwd()}", 'SKIP_DOCS=true']) {
          wrap([$class: 'AnsiColorBuildWrapper']) {
            sh './buildbot-scripts/jenkins_wrapper.sh'
          }
        }
      } finally {
        def cleanup = '''
          if hash lsof 2>/dev/null; then
            Z=$(lsof -d 200 -t)
            if [[ ! -z $Z ]]; then
              kill -9 $Z
            fi
          else
            echo "lsof is missing; unable to kill rebuild related processes."
          fi

          rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
        '''.stripIndent()

        withEnv(["WORKSPACE=${pwd()}"]) {
          sh cleanup
        }

        archiveArtifacts([
          artifacts: "lsstsw/build/manifest.txt",
          fingerprint: true
        ])
      } // try
    } // dir(python)
  } // node('osx')
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
def injectParam(String var) {
  if (binding.variables.containsKey(var)) {
    env."$var" = binding.variables[var]
  } else {
    echo "variable $var is not defined"
  }
}
