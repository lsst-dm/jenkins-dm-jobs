import util.Common
Common.makeFolders(this)

def j = job('sqre/infrastructure/build-codekit-triggers') {
  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  scm {
    git {
      remote {
        github('lsst-sqre/sqre-codekit')
        refspec('+refs/tags/*:refs/remotes/origin/tags/*')
      }
      branch('master')
    }
  } // scm

  triggers {
    githubPush()
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infrastructure/build-codekit') {}
    }
  }
}

Common.addNotification(j)
