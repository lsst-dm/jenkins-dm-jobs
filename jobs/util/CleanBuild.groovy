package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job

class CleanBuild {
  String name
  String product
  String branch = ''
  Boolean skipDemo = true
  Boolean skipDocs = true
  String cron = 'H 19 * * *'
  Object seedJob

  Job build(DslFactory dslFactory) {
    if (! name) {
      name = product
    }

    def j = dslFactory.pipelineJob(name) {
      properties {
        rebuild {
          autoRebuild()
        }
      }

      label('jenkins-master')
      keepDependencies()

      if (cron) {
        triggers {
          cron(cron)
        }
      }

      environmentVariables(
        PRODUCT: product,
        BRANCH: branch,
        SKIP_DEMO: skipDemo,
        SKIP_DOCS: skipDocs,
        WIPEOUT: true,
      )

      def repo = seedJob.scm.userRemoteConfigs.get(0).getUrl()
      def ref  = seedJob.scm.getBranches().get(0).getName()

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
          scriptPath("pipelines/stack_os_matrix.groovy")
        }
      } // definition
    } // pipelineJob
  } // build
} // CleanBuild
