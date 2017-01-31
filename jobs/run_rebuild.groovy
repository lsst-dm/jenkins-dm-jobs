import util.Common
Common.makeFolders(this)

def j = job('release/run-rebuild') {
  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
    fileParam('REPOS', 'repos.yaml')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  environmentVariables {
    env('EUPS_PKGROOT', '/lsst/distserver/production')
  }

  label('lsst-dev')
  concurrentBuild(false)
  customWorkspace('/home/lsstsw/jenkins/release')

  multiscm {
    git {
      remote {
        github('lsst/lsstsw')
      }
      branch('*/master')
      extensions {
        relativeTargetDirectory('lsstsw')
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
    sshAgent('github-jenkins-versiondb')
  }

  steps {
    shell(
      '''
      #!/bin/bash -e

      # ensure that we are using the lsstsw clone relative to the workspace
      # and that another value for LSSTSW isn't leaking in from the env
      export LSSTSW="${WORKSPACE}/lsstsw"

      # isolate eups cache files
      export EUPS_USERDATA="${WORKSPACE}/.eups"

      if [[ -e "${WORKSPACE}/REPOS" ]]; then
        export REPOSFILE="${WORKSPACE}/REPOS"
      fi

      ./buildbot-scripts/jenkins_wrapper.sh

      # handled by the postbuild on failure script if there is an error
      rm -rf "${WORKSPACE}/REPOS"
      '''.replaceFirst("\n","").stripIndent()
    )
  }

  publishers {
    postBuildScript {
      scriptOnlyIfSuccess(false)
      scriptOnlyIfFailure(true)
      markBuildUnstable(false)
      executeOn('AXES')
      buildStep {
        shell {
          command(
            '''
            Z=$(lsof -d 200 -t)
            if [[ ! -z $Z ]]; then
              kill -9 $Z
            fi

            rm -rf "${WORKSPACE}/stack/.lockDir"
            rm -rf "${WORKSPACE}/REPOS"
            '''.replaceFirst("\n","").stripIndent()
          )
        }
      }
    }

    archiveArtifacts {
      fingerprint()
      pattern('lsstsw/build/manifest.txt')
    }
  }
}

Common.addNotification(j)
