"""Read-only gate for idempotent tested-preview delivery. Never treat auth/network errors as absence."""
import json
import os
import sys
import urllib.error
import urllib.request

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def read(path):
    url = 'https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + '/' + path
    request = urllib.request.Request(url, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError('GitHub read failed with HTTP ' + str(error.code)) from None

def validate(get, tag, source, published=False):
    release = get('releases/tags/' + tag)
    ref = get('git/ref/tags/' + tag)
    if ref is not None:
        obj = ref['object']
        for _ in range(5):
            if obj['type'] == 'commit':
                break
            if obj['type'] != 'tag':
                raise RuntimeError('Unexpected tag object')
            obj = get('git/tags/' + obj['sha'])['object']
        if obj['type'] != 'commit' or obj['sha'] != source:
            raise RuntimeError('Existing tag points to a different source')
    if release is None:
        if ref is not None or published:
            raise RuntimeError('Release/tag state is inconsistent')
        return False
    if not release['prerelease']:
        raise RuntimeError('Refusing to modify a non-preview release')
    if release['draft']:
        if published or release['target_commitish'] != source:
            raise RuntimeError('Draft belongs to another source or is not published')
    elif ref is None:
        raise RuntimeError('Published release has no verified tag')
    return True

if __name__ == '__main__':
    print('exists' if validate(read, os.environ['PREVIEW_TAG'], os.environ['PREVIEW_SOURCE'], '--published' in sys.argv) else 'absent')
