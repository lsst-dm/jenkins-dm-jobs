freeStyleJob('seed-job') {
    node('jenkins-manager')
    scm {
        git {
            remote {
                name('origin')
                url('https://github.com/lsst-dm/jenkins-dm-jobs')
            }
            branches('tickets/DM-52263')
        }
    }
    steps {
        shell('./gradlew libs')
        dsl {
            external('jobs/*.groovy')
        }
    }
}
