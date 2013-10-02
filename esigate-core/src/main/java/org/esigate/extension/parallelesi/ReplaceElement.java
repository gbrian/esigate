/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.esigate.extension.parallelesi;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.esigate.HttpErrorPage;
import org.esigate.esi.EsiSyntaxError;
import org.esigate.parser.future.CharSequenceFuture;
import org.esigate.parser.future.FutureElementType;
import org.esigate.parser.future.FutureParserContext;
import org.esigate.parser.future.StringBuilderFutureAppendable;
import org.esigate.vars.VariablesResolver;

/**
 * Support for &lt;esi:replace&gt; element inside of parent &lt;esi:include&gt;
 * 
 * @author <a href="stanislav.bernatskyi@smile.fr">Stanislav Bernatskyi</a>
 */
class ReplaceElement extends BaseElement {

	public final static FutureElementType TYPE = new BaseElementType("<esi:replace", "</esi:replace") {
		public ReplaceElement newInstance() {
			return new ReplaceElement();
		}

	};

	private StringBuilderFutureAppendable buf = null;
	private String fragment;
	private String regexp;

	@Override
	public void characters(Future<CharSequence> csq) throws IOException {
		buf.enqueueAppend(csq);
	}

	@Override
	public void onTagEnd(String tag, FutureParserContext ctx) throws IOException, HttpErrorPage {
		IncludeElement parent = ctx.findAncestor(IncludeElement.class);
		if (parent == null)
			throw new EsiSyntaxError("<esi:replace> tag can only be used inside an <esi:include> tag");
		String result;
		try {
			result = VariablesResolver.replaceAllVariables(buf.get().toString(), ctx.getHttpRequest());
		} catch (InterruptedException e) {
			throw new IOException(e);
		} catch (ExecutionException e) {
			if( e.getCause() instanceof HttpErrorPage ) {
				throw (HttpErrorPage)e.getCause();
			}
			throw new IOException(e);
		}
		if (fragment != null) {
			parent.addFragmentReplacement(fragment, (CharSequence) result);
		} else if (regexp != null) {
			parent.addRegexpReplacement(regexp, (CharSequence) result);
		} else {
			parent.characters(new CharSequenceFuture(result));
		}
	}

	@Override
	protected void parseTag(Tag tag, FutureParserContext ctx) throws IOException, HttpErrorPage {
		buf = new StringBuilderFutureAppendable();
		fragment = tag.getAttribute("fragment");
		regexp = tag.getAttribute("regexp");
		if (regexp == null)
			regexp = tag.getAttribute("expression");
		if ((fragment == null && regexp == null) || (fragment != null && regexp != null)) {
			throw new EsiSyntaxError("only one of 'fragment' and 'expression' attributes is allowed");
		}
	}

}