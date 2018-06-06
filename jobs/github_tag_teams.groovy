import util.Plumber

def p = new Plumber(name: 'release/codekit/github-tag-teams', dsl: this)
p.pipeline().with {
  parameters {
    stringParam('GIT_TAG', null, 'git tag string. Eg. w.2016.08')
    booleanParam('DRY_RUN', true, 'Dry run')
  }
}
