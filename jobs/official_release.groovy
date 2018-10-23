import util.Plumber

def p = new Plumber(name: 'release/official-release', dsl: this)
p.pipeline().with {
  def text = '''
    Construct an official release from a pre-existing eups tag and
    corresponding manifest id.

    A new git repo tag will be applied AND a new eups tag will be published.
    The new eups tag is derived from the git tag name.

    Eg., (git tag) `16.0.0-rc1` -> (eups tag) `16_0_0_rc1`
  '''
  description(text.replaceFirst("\n","").stripIndent())

  parameters {
    stringParam('SOURCE_GIT_REFS', null,
      'existing git ref(s) upon which to base the release. Eg. "w.2018.22"')
    stringParam('RELEASE_GIT_TAG', null,
      'git tag for the new release. Eg. "v16.0.rc1"')
    booleanParam('O_LATEST', false,
      'update the eups "O_LATEST" tag -- should only be done for a final (non-rc) release')
  }
}
