import util.Common
Common.makeFolders(this)

def j = job('sqre/infra/build-newinstall-triggers') {
  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  scm {
    git {
      remote {
        github('lsst-sqre/docker-documenteer-base')
        branch('master')
      }
    }
  } // scm

  triggers {
    githubPush()
  }

  steps {
    downstreamParameterized {
      trigger('sqre/infra/build-documenteer-base') {}
    }
  }
}

Common.addNotification(j)
