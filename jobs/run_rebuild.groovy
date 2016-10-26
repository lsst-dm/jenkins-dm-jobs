import util.Common

def j = job('release/run-rebuild') {
  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  label('lsst-dev')
  concurrentBuild(false)
  customWorkspace('/home/lsstsw')

  multiscm {
    git {
      remote {
        github('lsst/lsstsw')
      }
      branch('*/master')
      extensions {
        cloneOptions { shallow() }
      }
    }
    git {
      remote {
        github('lsst-sqre/buildbot-scripts')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('buildbot-scripts')
        cloneOptions { shallow() }
      }
    }
  }

  triggers {
    cron('42 1,19 * * *')
  }

  wrappers {
    colorizeOutput('gnome-terminal')
  }

  steps {
    shell(
      '''
      #!/bin/bash -e

      export LSSTSW="$WORKSPACE"

      ./buildbot-scripts/jenkins_wrapper.sh
      '''.replaceFirst("\n","").stripIndent()
    )
  }

  publishers {
    archiveArtifacts('build/manifest.txt')
  }
}

Common.addNotification(j)
