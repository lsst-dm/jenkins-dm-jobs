import util.Common
Common.makeFolders(this)

def j = job('dax/release/rebuild_publish_qserv-dev-cron') {
  triggers {
    cron('0 12 * * 6')
  }

  steps {
    downstreamParameterized {
      trigger('dax/release/rebuild_publish_qserv-dev') {}
    }
  }
}

Common.addNotification(j)
