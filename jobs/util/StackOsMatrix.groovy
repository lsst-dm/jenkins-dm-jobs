package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job

class StackOsMatrix {
  String name
  String product
  Boolean skip_demo
  String cron = 'H H/8 * * *'
  String python = 'py2'
  String branch

  Job build(DslFactory dslFactory) {
    if (! name) {
      name = product
    }

    def j = dslFactory.job(name) {
      parameters {
        stringParam('BRANCH', branch, "Whitespace delimited list of 'refs' to attempt to build.  Priority is highest -> lowest from left to right.  'master' is implicitly appended to the right side of the list, if not specified.")
        stringParam('PRODUCT', product, 'Whitespace delimited list of EUPS products to build.')
      }

      // per request from user making multipushes to ci_hsc
      quietPeriod(1800)

      properties {
        rebuild {
          autoRebuild()
        }
      }
      concurrentBuild()
      label('jenkins-master')
      keepDependencies()

      triggers {
        cron(cron)
      }

      steps {
        downstreamParameterized {
          trigger('stack-os-matrix') {
            block {
              buildStepFailure('FAILURE')
              failure('FAILURE')
            }
            parameters {
              currentBuild()
              booleanParam('SKIP_DEMO', skip_demo)
              predefinedProp('python', python)
            }
          }
        }
      }
    }
    Common.addNotification(j)
  }
}
