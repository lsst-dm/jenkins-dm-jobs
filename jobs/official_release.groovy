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
    choiceParam('EUPSPKG_SOURCE', ['git', 'package'],
      'type of eupspkg to create -- "git" should always be used except for a final (non-rc) release')
    stringParam('SOURCE_EUPS_TAG', null,
      'existing eups tag upon which to base the release. Eg. "w_2018_22"')
    stringParam('SOURCE_MANIFEST_ID', null,
      'existing MANIFEST_ID/BUILD_ID/BUILD/bNNNN that corresponds to SOURCE_EUPS_TAG. Eg. "b3638"')
    stringParam('GIT_TAG', null,
      'git tag for the new release. Eg. "v16.0.rc1"')
    stringParam('EUPS_TAG', null,
      'eups tag for the new release. Eg. "v16_0_rc1"')
    booleanParam('O_LATEST', false,
      'update the eups "O_LATEST" tag -- should only be done for a final (non-rc) release')
    booleanParam('MANIFEST_ONLY', false, 'Do not cross reference a published eups tag with the manifest -- use only the metadata from the manifest to determine git tag location. This allows a git tag to be created without a prior eups tag.')
  }
}
