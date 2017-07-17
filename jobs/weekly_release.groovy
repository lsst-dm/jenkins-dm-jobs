import util.Common
Common.makeFolders(this)

def folder = 'release'

pipelineJob("${folder}/weekly-release-cron") {
  description('Periodically trigger the DM pipelines/dax "weekly".')

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  triggers {
    cron('0 0 * * 6')
  }

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath("pipelines/${folder}/weekly_release_cron.groovy")
    }
  }
}

pipelineJob("${folder}/weekly-release") {
  description('Tag and release the DM pipelines/dax "weekly".')

  parameters {
    stringParam('YEAR', null, 'Gregorian calendar year.')
    stringParam('WEEK', null, 'Week of Gregorian calendar year.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)
  keepDependencies(true)

  def repo = SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
  def ref  = SEED_JOB.scm.getBranches().get(0).getName()

  definition {
    cpsScm {
      scm {
        git {
          remote {
            url(repo)
          }
          branch(ref)
        }
      }
      scriptPath("pipelines/${folder}/weekly_release.groovy")
    }
  }
}
