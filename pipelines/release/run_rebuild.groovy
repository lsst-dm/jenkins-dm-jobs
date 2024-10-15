properties([
  copyArtifactPermission('/release/*'),
])

node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'REFS',
    'PREP_ONLY',
    'PRODUCTS',
    'BUILD_DOCS',
    'TIMEOUT',
  ])

  String refs       = params.REFS
  Boolean prepOnly  = params.PREP_ONLY
  String products   = params.PRODUCTS
  Boolean buildDocs = params.BUILD_DOCS
  Integer timelimit = Integer.parseInt(params.TIMEOUT)

  // not a normally exposed job param
  Boolean versiondbPush = (! params.NO_VERSIONDB_PUSH?.toBoolean())
  // default to safe
  def versiondbRepo = util.githubSlugToUrl(
    scipipe.versiondb.github_repo,
    'https'
  )
  if (versiondbPush) {
    versiondbRepo = util.githubSlugToUrl(scipipe.versiondb.github_repo, 'ssh')
  }

  def canonical    = scipipe.canonical
  def lsstswConfigs = canonical.lsstsw_config
  def cwd = '/j/' + canonical.workspace

  def buildParams = [
        EUPS_PKGROOT:        "${cwd}/distrib",
        GIT_SSH_COMMAND:     'ssh -o StrictHostKeyChecking=no',
        K8S_DIND_LIMITS_CPU: "4",
        LSST_BUILD_DOCS:     buildDocs,
        LSST_PREP_ONLY:      prepOnly,
        LSST_PRODUCTS:       products,
        LSST_REFS:           refs,
        // VERSIONDB_PUSH:      versiondbPush,
        // VERSIONDB_REPO:      versiondbRepo,
      ]
  
  // override conda env ref from build_matrix.yaml
  if (params.SPLENV_REF) {
    buildParams['LSST_SPLENV_REF'] = params.SPLENV_REF
  }

  if (lsstswConfigs == null) {
    error "invalid value for BUILD_CONFIG: ${BUILD_CONFIG}"
  }

  timeout(time: 12, unit: 'HOURS') {
    stage('build') {
      util.lsstswBuildMatrix(lsstswConfigs, buildParams )
    }
  }
} // notify.wrap
