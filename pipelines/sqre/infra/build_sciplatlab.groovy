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
  String push          = "true"
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
      println("url: ${url}")
      println("body: ${json}")

      def conn = url.openConnection().with { conn ->
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
        conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        conn.setRequestProperty('Authorization', "Bearer ${GITHUB_TOKEN}")
        conn.doOutput = true
        conn.outputStream << json.toPrettyString()

        assert (conn.responseCode == 204) : "API dispatch failed: ${conn.responseCode}|${conn.getInputStream().getText()}"
      } // openConnection().with
    } // stage

    stage('poll for job completion at GHA') {
      // We presume that GH Actions will continue to list most recent runs
      // at the top.  Poll the build.yaml runs for the first result.

      // I think we always want the prod branch.

      def url = new URL("https://api.github.com/repos/lsst-sqre/sciplat-lab/actions/workflows/build.yaml/runs?per_page=1&branch=prod")

      def status = ""
      def conclusion = ""
      def loop_idx = 0
      def jsonSlurper = new groovy.json.JsonSlurper()
      while (status != "completed") {
        if (loop_idx > 720) {
          assert 0: "GitHub Action did not complete in 2 hours: ${status}/${conclusion}"
        }
        Thread.sleep(10 * 1000)  // Good old sleep 10 (even the first time,
        // so the job has time to get started and we don't read the status
        // from the previous run).
        loop_idx += 1
        def conn = url.openConnection().with { conn ->
          conn.setRequestMethod('GET')
          conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
          conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
          def text = conn.getInputStream().getText()
          def obj = jsonSlurper.parseText(text)
          def wf = obj.workflow_runs[0]
          status = wf.status
          conclusion = wf.conclusion
          println("#{$loop_idx}: status=${status}; conclusion=${conclusion}")
        } // openConnection().with
      } // while
      assert (conclusion == 'success'): "Build failed: ${conclusion}"
    } // stage
  } // run

  util.withGithubAdminCredentials {
    run()
  }
} // notify.wrap
