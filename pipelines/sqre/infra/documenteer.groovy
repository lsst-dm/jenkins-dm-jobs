import groovy.transform.Field

node {
  if (params.WIPEOUT) {
    deleteDir()
  }

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
  }
}

notify.wrap {
  util.requireParams([
    'EUPS_TAG',
    'LTD_SLUG',
    'PUBLISH',
    'TEMPLATE_REF',
    'TEMPLATE_REPO',
  ])

  String eupsTag         = params.EUPS_TAG
  String ltdSlug         = params.LTD_SLUG
  Boolean publish        = params.PUBLISH
  String docTemplateRef  = params.TEMPLATE_REF
  String docTemplateRepo = util.githubSlugToUrl(params.TEMPLATE_REPO)

  // optional
  String relImage = params.RELEASE_IMAGE

  def dockerRepo = scipipe.scipipe_release.docker_registry.repo
  relImage = relImage ?: "${dockerRepo}:7-stack-lsst_distrib-${eupsTag}"

  def run = {
    def meerImage = null
    def docTemplateDir = "${pwd()}/doc_template"

    stage('prepare') {
      def config = util.dedent("""
        FROM ${relImage}

        USER    root
        RUN     yum -y install graphviz && yum -y clean all
      """)

      meerImage = "${relImage}-docubase"
      util.buildImage(
        config: config,
        tag: meerImage,
        pull: true,
      )
    } // stage

    dir(docTemplateDir) {
      stage('build docs') {
        deleteDir()

        git([
          url: docTemplateRepo,
          branch: docTemplateRef,
        ])

        util.runDocumenteer(
          docImage: meerImage,
          docTemplateDir: docTemplateDir,
          docPull: false,
          eupsTag: eupsTag,
        )
      } // stage

      stage('publish') {
        if (publish) {
          publishHTML([
            allowMissing: false,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: '_build/html',
            reportFiles: 'index.html',
            reportName: 'doc build',
            reportTitles: '',
          ])

          archiveArtifacts([
            artifacts: '_build/html/**/*',
            allowEmptyArchive: true,
            fingerprint: false,
          ])

          util.ltdPush(
            ltdProduct: "pipelines",
            repoSlug: "lsst/pipelines_lsst_io",
            ltdSlug: ltdSlug,
          )
        } // if
      } // stage
    } // dir
  } // run

  util.nodeWrap('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
