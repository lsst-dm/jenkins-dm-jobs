import util.Common
Common.makeFolders(this)

def j = job('qserv/release/tag-qserv_latest') {
  description('Publish a new "qserv_latest" eups distrib tag from the current master branch(s).')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  concurrentBuild(false)
  label('jenkins-master')
  keepDependencies()

  steps {
    downstreamParameterized {
      trigger('release/build_publish') {
        block {
          buildStepFailure('FAILURE')
          failure('FAILURE')
        }
        parameters {
          predefinedProp('PRODUCT', 'qserv_distrib')
          predefinedProp('EUPS_TAG', 'qserv_latest')
          booleanParam('SKIP_DEMO', true)
          booleanParam('SKIP_DOCS', true)
        }
      }
    }
  }
}

Common.addNotification(j)
