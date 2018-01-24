// slack plugin color codes
// https://github.com/jenkinsci/slack-plugin/blob/e568a98e4fbe93937f12d9ff22dad282c7e3d374/src/main/resources/jenkins/plugins/slack/workflow/SlackSendStep/help-color.html

String cause() {
  currentBuild.build().getAction(CauseAction.class).getShortDescription()
}

String duration() {
  def dur = currentBuild.build().getDurationString()

  // the build hasn't technically ended while the pipeline script is still
  // executing, so jenkins is appending "and counting" to the duration string
  def m = dur =~ /(.*) and counting/
  def durClean =  m[0][1]

  "after ${durClean}"
}

// AFAIK - gymnastics are required to get the build triggering user id without
// a workspace / shell env vars being setup; this seems unnecessarily difficult
// but it saves the overhead and latency of using a node block.
String jenkinsUserId() {
  currentBuild.build().getCause(Cause.UserIdCause).getUserId()
}

Map githubToSlack(String user) {
  def service    = 'https://api.lsst.codes/ghslacker'
  def authString = null

  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'ghslacker',
    usernameVariable: 'GS_USER',
    passwordVariable: 'GS_PASS'
  ]]) {
    authString = "${GS_USER}:${GS_PASS}".getBytes().encodeBase64().toString()
  }

  def conn = new URL("${service}/github/${user}/").openConnection()
  conn.setRequestProperty( "Authorization", "Basic ${authString}" )
  // DNS lookup failures will throw an exception
  def code = conn.getResponseCode()

  def response = [code: code]

  if (code.equals(200)) {
    def data    = conn.getInputStream().getText()
    def json    = new groovy.json.JsonSlurper().parseText(data)
    def slackId = json.collect { it.key }.first()
    response['slack_id'] = slackId
  }

  return response
}

String slackBaseMessage(String detail) {
  "${env.JOB_NAME} - #${env.BUILD_NUMBER} ${detail} (<${env.RUN_DISPLAY_URL}|Open>)"
}

String slackMessage(String detail) {
  try {
    def jenkinsId = jenkinsUserId()

    if (jenkinsId) {
      // end-user triggered build
      def response = githubToSlack(jenkinsId)

      if (response.code.equals(200)) {
        return "<@${response.slack_id}> ${slackBaseMessage(detail)}"
      }

      // if the slack lookup failed, send a message asking for the "github
      // user" to edit thier slack profile.
      if (response.code.equals(404)) {
        slackSend color: 'danger', message: "Hey, whoever is the slack user corresponding to the github user <${jenkinsId}>, please edit the GitHub Username field in your slack profile."
      } else {
        echo "HTTP status code ${response.code.toString()}"
      }
    }
  } catch (e) {
    // ignore all slack lookup related exceptions
    echo e.toString()
  }

  // build was not triggered by an end-user or slack lookup failed
  return slackBaseMessage(detail)
}

String slackStartMessage() {
  slackMessage(cause())
}

String slackSuccessMessage() {
  slackMessage("Success ${duration()}")
}

String slackFailureMessage() {
  slackMessage("Failure ${duration()}")
}

String slackAbortedMessage() {
  slackMessage("Aborted ${duration()}")
}

def started() {
  slackSend color: 'good', message: slackStartMessage()
}

def success() {
  slackSend color: 'good', message: slackSuccessMessage()
}

def aborted() {
  slackSend color: 'warning', message: slackAbortedMessage()
}

def failure() {
  slackSend color: 'danger', message: slackFailureMessage()
}

def wrap(Closure run) {
  try {
    started()

    run()
  } catch (e) {
    // If there was an exception thrown, the build failed
    currentBuild.result = "FAILED"
    throw e
  } finally {
    echo "result: ${currentBuild.result}"
    switch(currentBuild.result) {
      case null:
      case 'SUCCESS':
        success()
        break
      case 'ABORTED':
        aborted()
        break
      case 'FAILURE':
        failure()
        break
      default:
        failure()
    }
  }
}

return this;
