import util.Plumber

def p = new Plumber(name: 'release/codekit/github-tag-teams', dsl: this)
p.pipeline().with {
  def text = '''
    Tag the head of the default branch of all repositories in a GitHub org
    which belong to the specified team(s).

    Example:

      $ github-tag-release \
        --debug \
        --dry-run \
        --delete \
        --org 'lsst' \
        --allow-team 'DM Auxilliaries' \
        --deny-team 'DM Externals' \
        --token "$GITHUB_TOKEN" \
        --user 'sqreadmin' \
        --email 'sqre-admin@lists.lsst.org' \
        --tag 'w.9999.52'
  '''
  description(text.replaceFirst("\n","").stripIndent())

  parameters {
    stringParam('GIT_TAG', null, 'git tag string. Eg. w.9999.52')
    booleanParam('DRY_RUN', true, 'Dry run')
  }
}
