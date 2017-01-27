import util.Common
Common.makeFolders(this)

pipelineJob('release/build-publish') {
  description('Run release/run-rebuild & release/rub-publish in series')

  parameters {
    stringParam('BRANCH', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" is implicitly appended to the right side of the list, if not specified.')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to build.')
    stringParam('EUPS_TAG', null, 'EUPS tag string. Note that EUPS does not deal well with "." or "-" as part of a tag')
    booleanParam('SKIP_DEMO', false, 'Do not run the demo after all packages have completed building.')
    booleanParam('SKIP_DOCS', false, 'Do not build and publish documentation.')
  }

  properties {
    rebuild {
      autoRebuild()
    }
  }

  // don't tie up a beefy build slave
  label('jenkins-master')
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
      scriptPath('pipelines/build_publish.groovy')
    }
  }
}
