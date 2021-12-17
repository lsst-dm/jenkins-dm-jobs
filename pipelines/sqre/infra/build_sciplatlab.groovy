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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'TAG',
    'IMAGE',
    'SUPPLEMENTARY',
    'NO_PUSH',
    'BRANCH',
  ])

  String tag           = params.TAG
  String supplementary = params.SUPPLEMENTARY
  String image         = params.IMAGE
  String push	       = "true"
  String branch        = params.BRANCH

  if (params.NO_PUSH) {
    push = "false"
  }

  def run = {
    stage('trigger GHA') {
      def body = [
        ref: branch,
        inputs: [
          tag: tag,
          supplementary: supplementary,
          image: image,
	  push: push
        ]
      ]
      def json = new groovy.json.JsonBuilder(body)

      def url = new URL("https://api.github.com/repos/lsst-sqre/sciplat-lab/actions/workflows/build.yaml/dispatches")
      println("url: ${call}")
      println("body: ${json}")

      def conn = url.openConnection().with { conn ->
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
        conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        conn.setRequestProperty('Authorization', "Bearer ${GITHUB_TOKEN}")
        conn.doOutput = true
        conn.outputStream << json.toPrettyString()

        println("responseCode: ${conn.responseCode}")
        def text = conn.getInputStream().getText()
        println("response: ${text}")
      }
    }
  } // run


  util.withGithubAdminCredentials( {
    run()
  })
} // notify.wrap
