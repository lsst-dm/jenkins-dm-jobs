package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job

class CleanBuild {
  String name
  String products
  String refs = ''
  Boolean buildDocs = false
  String cron = 'H 19 * * *'
  String buildConfig = 'scipipe-lsstsw-matrix'
  Object seedJob

  Job build(DslFactory dslFactory) {
    if (! name) {
      name = products
    }

    def j = dslFactory.pipelineJob(name) {
      properties {
        rebuild {
          autoRebuild()
        }
      }

      keepDependencies()

      if (cron) {
        triggers {
          cron(cron)
        }
      }

      environmentVariables(
        PRODUCTS: products,
        REFS: refs,
        BUILD_DOCS: buildDocs,
        WIPEOUT: true,
        BUILD_CONFIG: buildConfig,
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
