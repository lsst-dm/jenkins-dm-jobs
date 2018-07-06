import util.Common
Common.makeFolders(this)

def j = job('sqre/infra/build-tag-monger-triggers') {
  scm {
    git {
      remote {
        github('lsst-sqre/tag-monger')
      }
      branch('master')
    }
  } // scm

  triggers {
    githubPush()
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infra/build-tag-monger') {}
    }
  }
}

Common.addNotification(j)
