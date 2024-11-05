import groovy.transform.Field
import org.codehaus.groovy.runtime.StackTraceUtils

@Field String slackEndpoint = 'https://slack.com/api'
@Field String checkerboardEndpoint = 'https://roundtable.lsst.cloud/checkerboard'
@Field Boolean debug = true


/*
 * methods in this section must not be coupled to jenkins or pipeline script so
 * that may be used/tested external to a jenkins pipeline job.
 */


/*
 * Return name of calling method
 *
 * import java.util.logging.Logger doesn't seem to work for outputting to a
 * pipeline console, so we have to roll our own debugging nonsense.
 * https://stackoverflow.com/a/23812177
 *
 * Note that marking methods as NonCPS will horribly break this.
 *
 * @return String name of calling method
 */
String getParentMethodName(){
  def marker = new Throwable()
  return StackTraceUtils.sanitize(marker).stackTrace[2].methodName
}

/*
 * print debugging info
 *
 * Switched on/off by the global `debug` variable
 *
 * @param value Object thing to print
 */
void debugln(Object value) {
  debug && println("${getParentMethodName()}: ${value}")
}

/*
 * Translate GitHub username to Slack username
 *
 * @param jenkins String user name (github)
 * @param authToken String checkerboard service token
 * @return String slack user Id
 */
String githubToSlack(String jenkinsUser, String authToken) {
  def url = new URL("${checkerboardEndpoint}/github/${jenkinsUser}")
  debugln("url: ${url.toString()}")

  def slackId = null
  url.openConnection().with { conn ->
    conn.setRequestProperty('Authorization', "Bearer ${authToken}")

    debugln("responseCode: ${conn.responseCode}")
    if (conn.responseCode.equals(200)) {
      // don't try to parse body text upon error
      def text = conn.getInputStream().getText()
      debugln("text: ${text}")
      def data = new groovy.json.JsonSlurperClassic().parseText(text)
      slackId  = data.collect { it.key }.first()
    }
  }

  return slackId
}

/*
 * convert map into URL query string
 *
 * query values are URL encoded
 *
 * @param data Map key/value pairs to form query
 * @return String slack user Id
 */
String mapToArg(Map data) {
  data.collect { k,v ->
    "${k}=${URLEncoder.encode(v)}"
  }.join('&')
}

/*
 * make a slack ReST API call (get)
 *
 * @param args.method String slack api method (required)
 * @param args.query Map of query params (must include oauth token) (required)
 * @param args.dieHard Boolean Throw exception on error (optional)
 * @return Object response data (parsed json)
 */
Object slackApiGet(Map args) {
  requiredParams(['method', 'query'], args)

  // token is not allowed in query parameters anymore
  token = args.query.token
  args.query.remove("token")
  def call = "${slackEndpoint}/${args.method}?${mapToArg(args.query)}"

  url = new URL(call)
  debugln("url: ${url.toString()}")
  debugln("token: ${token.substring(0,8)}")

  def data = null
  def conn = url.openConnection().with { conn ->
    conn.setRequestProperty('Authorization', "Bearer ${token}")
    debugln("responseCode: ${conn.responseCode}")
    def text = conn.getInputStream().getText()
    debugln("text: ${text}")
    // assuming that slack always returns valid json upon http 200
    if (conn.responseCode.equals(200)) {
      data = new groovy.json.JsonSlurperClassic().parseText(text)
      if (args?.dieHard && !data.ok) {
        throw new Error("Failed - status: ${conn.responseCode}, text: ${text}")
      }
    } else if (args?.dieHard) {
      throw new Error("Failed - status: ${conn.responseCode}, text: ${text}")
    }
  }

  return data
}

/*
 * make a slack ReST API call (post)
 *
 * @param args.method String slack api method (required)
 * @param args.token String slack oauth token (required)
 * @param args.body Map of key/value pairs (required)
 * @param args.dieHard Boolean Throw exception on error (optional)
 * @return Object response data (parsed json)
 */
Object slackApiPost(Map args) {
  requiredParams(['method', 'token', 'body'], args)

  def json = new groovy.json.JsonBuilder(args.body)

  def call = "${slackEndpoint}/${args.method}"
  url = new URL(call)
  debugln("url: ${url.toString()}")
  debugln("token: ${args.token.substring(0,8)}")

  def data = null
  def conn = url.openConnection().with { conn ->
    conn.setRequestMethod('POST')
    conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
    conn.setRequestProperty('Authorization', "Bearer ${args.token}")
    conn.doOutput = true
    conn.outputStream << json.toPrettyString()

    debugln("post: ${conn.outputStream}")
    debugln("responseCode: ${conn.responseCode}")
    def text = conn.getInputStream().getText()
    debugln("text: ${text}")
    // assuming that slack always returns valid json upon http 200
    if (conn.responseCode.equals(200)) {
      data = new groovy.json.JsonSlurperClassic().parseText(text)
      if (args?.diehard && !data.ok) {
        throw new Error("Failed - status: ${conn.responseCode}, text: ${text}")
      }
    } else if (args?.dieHard) {
      throw new Error("Failed - status: ${conn.responseCode}, text: ${text}")
    }
  }

  return data
}

/*
 * Create a slack channnel
 *
 * @param args.name String name of channel (required)
 * @param args.token String slack oauth token (required)
 * @param args.topic String channel topicpurpose (optional)
 * @param args.purpose String channel purpose (optional)
 * @return Object channel data (parsed json)
 */
Object createChannel(Map args) {
  requiredParams(['name', 'token'], args)

  // https://api.slack.com/methods/conversations.create
  def create = slackApiPost(
    method: 'conversations.create',
    token: args.token,
    body: [
      name: args.name,
    ],
  )

  if (create?.ok) {
    def channelId = create.channel.id

    // https://api.slack.com/methods/conversations.setTopic
    if (args.topic) {
      slackApiPost(
        method: 'conversations.setTopic',
        token: args.token,
        body: [
          channel: channelId,
          topic: args.topic,
        ],
        dieHard: true,
      )
    }

    // https://api.slack.com/methods/conversations.setPurpose
    if (args.purpose) {
      slackApiPost(
        method: 'conversations.setPurpose',
        token: args.token,
        body: [
          channel: channelId,
          purpose: args.purpose,
        ],
        dieHard: true,
      )
    }

    // this prevents the user that added the slack app from ending up in every
    // channel created.  The channel can only be left after the topic/purpose
    // is set as otherwise these calls will fail.
    // https://api.slack.com/methods/conversations.leave
    slackApiPost(
      method: 'conversations.leave',
      token: args.token,
      body: [
        channel: channelId,
      ],
      dieHard: true,
    )
  }

  return create
}

/*
 * Lookup slack user profile
 *
 * @param token String oauth token
 * @param slackId String slack user id
 * @return Map slack profile data
 */
Map slackUserProfile(String token, String slackId) {
  slackApiGet(
    method: 'users.profile.get',
    query: [
      token: token,
      user: slackId,
    ],
    dieHard: true
  )
}

/*
 * Mangle jenkins folder path into a short form
 *
 * @param folder String folder path
 * @return String short form
 */
String shortenFolder(String folder) {
  def dirsep = '_'

  folder.split('/').collect { section ->
    // take first char
    section[0]
  }.join(dirsep)
}

/*
 * Mangle jenkins job name into a short form
 *
 * @param name String job name
 * @param length Integer max number of allowed chars
 * @return String short form
 */
String shortenJobName(String name, Integer length) {
  def space = length

  def words = name.split('-')
  space -= (words.size() - 1) // account for word sep chars
  Integer cpw = space / words.size()
  name.split('-').collect { word -> word.take(cpw) }.join('-')
}

/*
 * Die if fequired keys in a Map are abasent
 *
 * @param need List of key names (String)
 * @param args Map to inspect
 */
def requiredParams(List need, Map args) {
  def caller = getParentMethodName()

  need.each {
    if (!args.containsKey(it)) {
      throw new Error("${caller}: key ${it} is required")
    }
  }
}


/*
 * Methods below are coupled to jenkins
 */


/*
 * Build cause -- Ie what triggered the build
 *
 * @return String cause of build
 */
String cause() {
  currentBuild.build().getAction(CauseAction.class).getShortDescription()
}

/*
 * Duration of current build
 *
 * @return String duration
 */
String duration() {
  def dur = currentBuild.build().getDurationString()

  // the build hasn't technically ended while the pipeline script is still
  // executing, so jenkins is appending "and counting" to the duration string
  def m = dur =~ /(.*) and counting/
  def durClean =  m[0][1]

  "after ${durClean}"
}

/*
 * Jenkins user which triggered the build (if any)
 *
 * AFAIK - gymnastics are required to get the build triggering user id without
 * a workspace / shell env vars being setup; this seems unnecessarily difficult
 * but it saves the overhead and latency of using a node block.
 *
 * @return String user id
 */
String jenkinsUserId() {
  currentBuild.build().getCause(Cause.UserIdCause)?.getUserId()
}

/*
 * Send a slack message warning about no current slack user having a github
 * field that matches the jenkins (github oauth) user.
 *
 * @param token String slack oauth token
 * @param channel String slack channel name (or id)
 * @param jenkinsId String jenkins user id
 */
def warnMissingGithubUser(String token, String channel, String jenkinsId) {
  def excludeList = ['PaulPrice', 'tgoldina']
  if (excludeList.contains(jenkinsId)) {
    return
  }
  def message = baseBuildMessage(
    channel: channel,
    color:   'danger',
    detail:  "Hey, whoever is the slack user corresponding to the github user <${jenkinsId}>, please edit the `GitHub username` field in your slack profile.",
  )

  slackApiPost(
    method: 'chat.postMessage',
    token: token,
    body: message,
    dieHard: true,
  )
}

/*
 * Generate a data structure representing a slack message "attachment".
 *
 * @param args.detail String build details messages
 * @param args.channel String slack channel name
 * @param args.color String slack color code
 * @param jenkinsId String jenkins user id
 * @return Map slack attachment
 */
Map baseBuildMessage(Map args) {
  requiredParams(['detail', 'channel', 'color'], args)

  def message = [
    channel: args.channel,
    attachments: [
      [
        mrkdwn:     true,
        fallback:   "#${env.BUILD_NUMBER} - ${args.detail}",
        color:      args.color,
        title:      "#${env.BUILD_NUMBER}",
        title_link: env.RUN_DISPLAY_URL,
        text:       "_${args.detail}_",
      ],
    ], // attachments
  ]

  if (!params.isEmpty()) {
    message.attachments.first().text += " ```\n" +
      params.collect{ k,v -> "${k}: ${v}" }.join("\n") +
      "\n```"
  }

  return message
}

String defaultChannel() {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'slack-default-channel',
    variable: 'channel'
  ]]) {
    return channel
  }
}

String jobChannelPrefix() {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'slack-channel-prefix',
    variable: 'channelPrefix'
  ]]) {
    return channelPrefix
  }
}

String jobChannel() {
  def maxChannelChars = 80
  def parts = []

  parts << jobChannelPrefix()
  parts << '-'

  // if JOB_NAME and JOB_BASE_NAME are identifcal, there is no folder path
  // component
  if (env.JOB_NAME != env.JOB_BASE_NAME) {
    // JOB_NAME is <folder path>/<job name>; <job name> needs to be striped off
    def folder = env.JOB_NAME.split('/')[0 .. -2].join('/')

    parts << shortenFolder(folder)
    parts << '_'
  }

  def charsAvailable = maxChannelChars - parts.join('').size()

  if (env.JOB_BASE_NAME.size() <= charsAvailable) {
    parts << env.JOB_BASE_NAME
  } else {
    parts << shortenJobName(env.JOB_BASE_NAME, charsAvailable)
  }

  def channel = parts.join('')
  // probably not nessicary...
  channel = channel.take(maxChannelChars)

  // do not allow trailing -
  channel.replaceAll('-$', '')
}

String githubToSlackEz(jenkinsId) {
  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'ghslacker',
    usernameVariable: 'CB_USER',
    passwordVariable: 'CB_PASS'
  ]]) {
    return githubToSlack(jenkinsId, CB_PASS)
  }
}

// memoized to reduce remote api calls
@Field Map slackProfile = null
@Field String slackId = null
@Field Boolean warningIssued = false
// memoized to reduce the number of withCredentials steps reported to console
@Field String jobChannel = null
@Field String defaultChannel = null
void slackSendBuild(Map args) {
  // required keys:
  // color
  // detail
  requiredParams(['color', 'detail'], args)

  defaultChannel = defaultChannel ?: defaultChannel()
  jobChannel = jobChannel ?: jobChannel()

  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'slack-lsstc-token',
    variable: 'SLACK_TOKEN'
  ]]) {
    def jenkinsId = jenkinsUserId()
    if (jenkinsId) {
      // end-user triggered build
      slackId = slackId ?: githubToSlackEz(jenkinsId)
      debugln("slackId: ${slackId}")

      if (!slackId && !warningIssued) {
        // only issue warning once per build to avoid spamming
        warnMissingGithubUser(SLACK_TOKEN, defaultChannel, jenkinsId)
        warningIssued = true
      }
    }

    def message = baseBuildMessage(
      channel: jobChannel,
      color:   args.color,
      detail:  args.detail,
    )

    if (slackId) {
      // add @user ping to message
      message.attachments.first() << [
        footer: "<@${slackId}>",
      ]

      slackProfile = slackProfile ?: slackUserProfile(SLACK_TOKEN, slackId)
      message.attachments.first() << [
        footer_icon: slackProfile.profile.image_24,
      ]
    }

    def sendMessage = {
      def send = slackApiPost(
        method: 'chat.postMessage',
        token: SLACK_TOKEN,
        body: message,
      )

      // invite user to channel -- if the user is not in the channel or
      // invited, they will not received a pop-up notification
      // also note that an invite should be sent with every @user message
      // incase there has been a long period between the build start and
      // completion.
      if (send?.ok && slackId) {
        // https://api.slack.com/methods/conversations.invite
        // note that this method seems to only work with a channel ID
        def invite = slackApiPost(
          method: 'conversations.invite',
          token: SLACK_TOKEN,
          body: [
            channel: send.channel,
            users: slackId,
          ],
        )

        // ignore 'already_in_channel' error; puke otherwise
        if (invite?.errors) {
          invite.errors.each { e ->
            if (e.error != 'already_in_channel') {
              throw new Error("error != 'already_in_channel': ${invite}")
            }
          }
        }
      }

      return send
    }

    // try to send message to channel
    def send = sendMessage()
    if (send && !send?.ok) {
      if (send?.error == 'channel_not_found') {
        createChannel(
          token: SLACK_TOKEN,
          name: jobChannel,
          topic: "${env.JOB_NAME} - ${env.JOB_DISPLAY_URL}",
          purpose: "Jenkins ${env.JOB_NAME} job related notifications",
        )
        sendMessage()
      } else {
        echo "failed to send message: ${send}"
      }
    }
  } // withCredentials
}

String slackStartMessage() {
  cause()
}

String slackSuccessMessage() {
  "Success ${duration()}"
}

String slackFailureMessage() {
  "Failure ${duration()}"
}

String slackAbortedMessage() {
  "Aborted ${duration()}"
}

def started() {
  slackSendBuild(color: 'good', detail: slackStartMessage())
}

def success() {
  slackSendBuild(color: 'good', detail: slackSuccessMessage())
}

def aborted() {
  slackSendBuild(color: 'warning', detail: slackAbortedMessage())
}

def failure() {
  slackSendBuild(color: 'danger', detail: slackFailureMessage())
}

def trynotify(Closure run) {
  // if (debug) {
  //   run()
  // } else {
    try {
      run()
    } catch (org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException e) {
      // fail on groovy sandbox exceptions. ie., methods that need to be
      // whitelisted
      throw e
    } catch (Error e) {
      // ignore other exception so problems with slack messaging will not cause
      // the build to be marked as a failure.
      echo "error sending slack notification: ${e.toString()}"
    }
  // }
}

def wrap(Closure run) {
  try {
    trynotify { started() }

    run()
  } catch (e) {
    // If there was an exception thrown, the build failed
    currentBuild.result = "FAILED"
    throw e
  } finally {
    echo "result: ${currentBuild.result}"

    trynotify {
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
}

return this;
