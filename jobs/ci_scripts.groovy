import util.Common
Common.makeFolders(this)

multibranchPipelineJob('sqre/infra/ci-scripts') {
  branchSources {
    github {
      repoOwner('lsst-sqre')
      repository('ci-scripts')
      scanCredentialsId('github-user-sqreadmin')
    }
  }
}
