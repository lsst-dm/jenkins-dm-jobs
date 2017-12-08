import util.Common
Common.makeFolders(this)

multibranchPipelineJob('sqre/infrastructure/ci-scripts') {
  branchSources {
    github {
      repoOwner('lsst-sqre')
      repository('ci-scripts')
      scanCredentialsId('github-user-sqreadmin')
    }
  }
}
