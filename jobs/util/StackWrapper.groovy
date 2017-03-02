package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job

class StackWrapper {
  String product
  Boolean skip_demo
  String cron = 'H H/8 * * *'
  String python = 'py2'

  Job build(DslFactory dslFactory) {
    def j = dslFactory.job(product) {
      parameters {
        stringParam('BRANCH', null, "Whitespace delimited list of 'refs' to attempt to build.  Priority is highest -> lowest from left to right.  'master' is implicitly appended to the right side of the list, if not specified.")
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
              predefinedProp('PRODUCT', product)
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
