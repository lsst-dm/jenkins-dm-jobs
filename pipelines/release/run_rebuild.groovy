properties([
  copyArtifactPermission('/release/*'),
]);

node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
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
  Boolean nobinary = params.NO_BINARY_FETCH
  Boolean glibcFlag = params.GLIBC_FLAG
  Integer timelimit = Integer.parseInt(params.TIMEOUT)

  // EUPS distrib publish is folded into this job so it runs in the same pod as
  // the build -- the freshly-deployed lsstsw/miniconda and eups-installed stack
  // only exist inside the build pod, so a separate publish pod cannot see them.
  Boolean publish        = params.PUBLISH?.toBoolean()
  String saveCacheTag    = params.SAVE_CACHE_TAG ?: ''
  String eupsTags        = params.EUPS_TAG ?: ''
  String eupspkgSource   = params.EUPSPKG_SOURCE ?: 'git'

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
  def lsstswConfig = canonical.lsstsw_config

  def splenvRef = lsstswConfig.splenv_ref
  if (params.SPLENV_REF) {
    splenvRef = params.SPLENV_REF
  }

  def rubinEnvVer = splenvRef
  if (params.RUBINENV_VER) {
    rubinEnvVer = params.RUBINENV_VER
  }

  def slug = util.lsstswConfigSlug(lsstswConfig)

  def run = {
    // Everything -- build and push docs -- runs inside ONE pod sharing the
    // emptyDir workspace. jenkinsWrapper() clones ci-scripts and produces
    // DOC_PUSH_PATH inside the build container; the push docs stage reads both
    // via the gcloud-cli sidecar (cacheImage), so a single pod is required.
    util.insideK8sContainer(
      image: lsstswConfig.image,
      pull: true,
      cacheImage: util.defaultGcloudCliImage(),
    ) {
      ws(canonical.workspace) {
        def cwd = pwd()

        def buildParams = [
          EUPS_PKGROOT:          "${cwd}/distrib",
          GIT_SSH_COMMAND:       'ssh -o StrictHostKeyChecking=no',
          K8S_DIND_LIMITS_CPU:   "4",
          LSST_BUILD_DOCS:       buildDocs,
          LSST_COMPILER:         lsstswConfig.compiler,
          LSST_JUNIT_PREFIX:     slug,
          LSST_PREP_ONLY:        prepOnly,
          LSST_NO_BINARY_FETCH:  nobinary,
          LSST_GLIBC_FLAG:       glibcFlag,
          LSST_PRODUCTS:         products,
          LSST_PYTHON_VERSION:   lsstswConfig.python,
          LSST_SPLENV_REF:       splenvRef,
          LSST_REFS:             refs,
          LSST_ADD_RSP:          true,
          VERSIONDB_PUSH:        versiondbPush,
          VERSIONDB_REPO:        versiondbRepo,
        ]

        def runJW = {
          // note that util.jenkinsWrapper() clones the ci-scripts repo, which is
          // used by the push docs stage
          try {
            util.jenkinsWrapper(buildParams)
          } finally {
            util.jenkinsWrapperPost(null, prepOnly)
          }
        }

        def withVersiondbCredentials = { closure ->
          sshagent (credentials: ['github-jenkins-versiondb']) {
            closure()
          }
        }

        stage('build') {
          // only setup sshagent if we are going to push
          if (versiondbPush) {
            withVersiondbCredentials(runJW)
          } else {
            runJW()
          }
        } // stage('build')

        // Upload the lsstsw tree just built here so downstream consumers
        // (sonar-scan, stack-os-matrix LOAD_CACHE) do not have to rebuild the
        // same products a second time. The tag is a per-build temporary one; the
        // calling release pipeline promotes it to d_latest only after the rest of
        // the release succeeds, so a failed release cannot leave the shared cache
        // pointing at a stack that was never published.
        stage('save cache') {
          if (saveCacheTag && !prepOnly) {
            util.saveCache(saveCacheTag)
          }
        } // stage('save cache')

        stage('push docs') {
          if (buildDocs) {
            withCredentials([file(
              credentialsId: 'gs-eups-push',
              variable: 'GOOGLE_APPLICATION_CREDENTIALS'
            ),
            [
              $class: 'StringBinding',
              credentialsId: 'doxygen-push-bucket',
              variable: 'DOXYGEN_S3_BUCKET'
            ]]) {
              withEnv([
                "EUPS_PKGROOT=${cwd}/distrib",
                "HOME=${cwd}/home",
              ]) {

                container('gcloud-cli') {
                  // alpine does not include bash by default
                  util.posixSh '''
                    # provides DOC_PUSH_PATH
                    . ./ci-scripts/settings.cfg.sh
                    gcloud auth activate-service-account eups-dev@prompt-proto.iam.gserviceaccount.com --key-file=$GOOGLE_APPLICATION_CREDENTIALS;

                    gcloud storage cp \
                      --recursive \
                      "${DOC_PUSH_PATH}/*" \
                      "gs://${DOXYGEN_S3_BUCKET}/stack/doxygen/"
                  '''
                } // container('gcloud-cli')
              } // withEnv
            } // withCredentials
          }
        } // stage('push docs')

        // Publish EUPS distrib packages from the stack just built in this pod.
        // The manifest id is read in-pod from the build output rather than
        // round-tripped through a separate job. publish() reconstructs source
        // distrib tarballs from the eups-installed products, so it must run here
        // where ./lsstsw/miniconda and the installed stack live.
        stage('eups publish') {
          if (publish && !prepOnly) {
            def pkgroot = "${cwd}/distrib"
            def manifestId = util.parseManifestId(
              readFile("${cwd}/lsstsw/build/manifest.txt")
            )

            // remove any pre-existing eups tags to prevent them from being
            // [re]published (the src pkgroot has tags under ./tags/)
            dir("${pkgroot}/tags") {
              deleteDir()
            }

            eupsTags.tokenize().each { eupsTag ->
              withEnv([
                "HOME=${cwd}/home",
                "EUPS_PKGROOT=${pkgroot}",
                "EUPS_USERDATA=${cwd}/home/.eups_userdata",
                "EUPSPKG_SOURCE=${eupspkgSource}",
                "LSST_SPLENV_REF=${splenvRef}",
                "RUBINENV_VER=${rubinEnvVer}",
                "MANIFEST_ID=${manifestId}",
                "EUPS_TAG=${eupsTag}",
                "PRODUCTS=${products}",
              ]) {
                // local retry so a transient publish hiccup does not bubble up
                // and trigger a full (multi-hour) rebuild retry in the caller.
                retry(3) {
                  util.bash '''
                    ARGS=()
                    ARGS+=('-b' "$MANIFEST_ID")
                    ARGS+=('-t' "$EUPS_TAG")
                    # enable debug output
                    ARGS+=('-d')
                    # split whitespace separated EUPS products into separate array
                    # elements by not quoting
                    ARGS+=($PRODUCTS)

                    export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

                    source ./lsstsw/bin/envconfig -n "lsst-scipipe-$LSST_SPLENV_REF"

                    publish "${ARGS[@]}"
                  '''
                } // retry
              } // withEnv
            } // eupsTags.each
          }
        } // stage('eups publish')

        stage('push packages gcp') {
          if (publish && !prepOnly) {
            withCredentials([file(
              credentialsId: 'gs-eups-push',
              variable: 'GOOGLE_APPLICATION_CREDENTIALS'
            )]) {
              withEnv([
                "EUPS_PKGROOT=${cwd}/distrib",
                "HOME=${cwd}/home",
                "EUPS_GS_OBJECT_PREFIX=stack/src/",
                "EUPS_GS_BUCKET=eups-prod",
              ]) {
                retry(3) {
                  container('gcloud-cli') {
                    // alpine does not include bash by default
                    util.posixSh '''
                      gcloud auth activate-service-account eups-dev@prompt-proto.iam.gserviceaccount.com --key-file=$GOOGLE_APPLICATION_CREDENTIALS;
                      gcloud storage cp \
                        --recursive \
                        "${EUPS_PKGROOT}/*" \
                        "gs://${EUPS_GS_BUCKET}/${EUPS_GS_OBJECT_PREFIX}"
                    '''
                  } // container('gcloud-cli')
                } // retry
              } // withEnv
            } // withCredentials
          }
        } // stage('push packages gcp')
      } // ws
    } // util.insideK8sContainer
  } // run

  timeout(time: timelimit, unit: 'HOURS') {
    run()
  }
} // notify.wrap
