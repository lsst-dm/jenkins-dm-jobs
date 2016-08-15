def started() {
  hipchatSend (color: 'GREEN', notify: false, message: "${env.JOB_NAME} #${env.BUILD_NUMBER} Build started (\$CHANGES_OR_CAUSE) (<a href=\"${env.BUILD_URL}\">Job</a>)  (<a href=\"${env.BUILD_URL}/console\">Console</a>)")
}

def success() {
  hipchatSend (color: 'GREEN', notify: false, message: "${env.JOB_NAME} #${env.BUILD_NUMBER} Build sucessful after \$DURATION (<a href=\"${env.BUILD_URL}\">Job</a>) (<a href=\"${env.BUILD_URL}/console\">Console</a>)")
}

def aborted() {
  hipchatSend (color: 'GRAY', notify: true , message: "${env.JOB_NAME} #${env.BUILD_NUMBER} Build aborted after \$DURATION (<a href=\"${env.BUILD_URL}\">Job</a>) (<a href=\"${env.BUILD_URL}/console\">Console</a>)")
}

def failure() {
  hipchatSend (color: 'RED', notify: true , message: "${env.JOB_NAME} #${env.BUILD_NUMBER} Build failed after \$DURATION (<a href=\"${env.BUILD_URL}\">Job</a>) (<a href=\"${env.BUILD_URL}/console\">Console</a>)")
}

return this;
