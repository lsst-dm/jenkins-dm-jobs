freeStyleJob('jc-test') {
    scm {
        git {
            remote {
                name('origin')
                url('https://github.com/lsst-dm/jenkins-dm-jobs')
            }
            branches('update-gradle')
        }
    }
    steps {
        shell('./gradlew libs')
        dsl {
            external('jobs/*.groovy')
        }
    }
}
