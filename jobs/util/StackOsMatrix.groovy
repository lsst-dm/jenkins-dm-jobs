package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job

class StackOsMatrix {
  String name
  List<String> pys = ['py2']
  String product = 'lsst_sims lsst_distrib'

  Job build(DslFactory dslFactory) {
    def j = dslFactory.matrixJob(name) {
      parameters {
        stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
        stringParam('PRODUCT', product, 'Whitespace delimited list of EUPS products to build.')
        booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
        booleanParam('NO_FETCH', false, 'Do not pull from git remote if branch is already the current ref. (This should generally be false outside of testing the CI system)')
      }

      properties {
        rebuild {
          autoRebuild()
        }
      }

      label('master')
      concurrentBuild()

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

      // setup varargs for 'python' axis
      pys.addAll(0, 'python')

      axes {
        label('label',
          'centos-6', 'centos-7',
          'osx'
        )
        text(*pys)
      }

      combinationFilter('''
        !(
          (label=="centos-6" && python=="py3") ||
          (label=="osx" && python=="py3")
        )
      '''.replaceFirst("\n","").stripIndent())

      wrappers {
        colorizeOutput('gnome-terminal')
      }

      environmentVariables(
        SKIP_DOCS: true,
      )

      steps {
        shell('./buildbot-scripts/jenkins_wrapper.sh')
      }

      publishers {
        // we have to use postBuildScript here instead of the friendlier
        // postBuildScrips (plural) in order to use executeOn(), otherwise the
        // cleanup script is also run on the jenkins master
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

                rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
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
  }
}
