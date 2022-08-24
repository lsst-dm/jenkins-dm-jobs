package util

import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.Job
import util.Common

class Plumber {
  String name
  String script
  DslFactory dsl

  def script() {
    if (!script) {
      return "pipelines/${name.tr('-', '_')}.groovy"
    }
    script
  }

  Job pipeline() {
    Common.makeFolders(dsl)

    dsl.pipelineJob(name) {
      keepDependencies()
      properties {
        disableConcurrentBuilds()
      }

      def repo = dsl.SEED_JOB.scm.userRemoteConfigs.get(0).getUrl()
      def ref  = dsl.SEED_JOB.scm.getBranches().get(0).getName()

      logRotator {
        artifactDaysToKeep(365)
        daysToKeep(730)
      }

      definition {
        cpsScm {
          scm {
            git {
              remote {
                url(repo)
              }
              branch(ref)
              extensions {
                pathRestriction {
                  includedRegions(script())
                  excludedRegions(null)
                }
              }
            }
          }
          scriptPath(script())
        }
      } // definition
    } // pipelineJob
  } // pipeline
} // class
