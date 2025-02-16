/*
 * Copyright 2004 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * ReplaceMessages replaces user-visible messages with alternatives. It uses Google specific
 * JsMessageVisitor implementation.
 */
@GwtIncompatible("JsMessage")
public final class ReplaceMessages extends JsMessageVisitor {
  private final MessageBundle bundle;
  private final boolean strictReplacement;

  public static final DiagnosticType BUNDLE_DOES_NOT_HAVE_THE_MESSAGE =
      DiagnosticType.error(
          "JSC_BUNDLE_DOES_NOT_HAVE_THE_MESSAGE",
          "Message with id = {0} could not be found in replacement bundle");

  public static final DiagnosticType INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS =
      DiagnosticType.error(
          "JSC_INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS",
          "Alternate message ID={0} placeholders ({1}) differs from {2} placeholders ({3}).");

  ReplaceMessages(
      AbstractCompiler compiler,
      MessageBundle bundle,
      JsMessage.Style style,
      boolean strictReplacement) {
    super(compiler, style, bundle.idGenerator());

    this.bundle = bundle;
    this.strictReplacement = strictReplacement;
  }

  private JsMessage lookupMessage(Node callNode, MessageBundle bundle, JsMessage message) {
    JsMessage translatedMessage = bundle.getMessage(message.getId());
    if (translatedMessage != null) {
      return translatedMessage;
    }

    String alternateId = message.getAlternateId();
    if (alternateId == null) {
      return null;
    }

    JsMessage alternateMessage = bundle.getMessage(alternateId);
    if (alternateMessage != null) {
      // Validate that the alternate message is compatible with this message. Ideally we'd also
      // check meaning and description, but they're not populated by `MessageBundle.getMessage`.
      if (!Objects.equals(message.placeholders(), alternateMessage.placeholders())) {
        compiler.report(
            JSError.make(
                callNode,
                INVALID_ALTERNATE_MESSAGE_PLACEHOLDERS,
                alternateId,
                String.valueOf(alternateMessage.placeholders()),
                message.getKey(),
                String.valueOf(message.placeholders())));
        return null;
      }
    }
    return alternateMessage;
  }

  @Override
  void processMessageFallback(Node callNode, JsMessage message1, JsMessage message2) {
    boolean isFirstMessageTranslated = (lookupMessage(callNode, bundle, message1) != null);
    boolean isSecondMessageTranslated = (lookupMessage(callNode, bundle, message2) != null);
    Node replacementNode =
        (isSecondMessageTranslated && !isFirstMessageTranslated)
            ? callNode.getChildAtIndex(2)
            : callNode.getSecondChild();
    callNode.replaceWith(replacementNode.detach());
    Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(replacementNode);
    if (changeScope != null) {
      compiler.reportChangeToChangeScope(changeScope);
    }
  }

  @Override
  protected void processJsMessage(JsMessage message, JsMessageDefinition definition) {

    // Get the replacement.
    Node callNode = definition.getMessageNode();
    JsMessage replacement = lookupMessage(callNode, bundle, message);
    if (replacement == null) {
      if (strictReplacement) {
        compiler.report(JSError.make(callNode, BUNDLE_DOES_NOT_HAVE_THE_MESSAGE, message.getId()));
        // Fallback to the default message
        return;
      } else {
        // In case if it is not a strict replacement we could leave original
        // message.
        replacement = message;
      }
    }

    // Replace the message.
    Node newValue;
    Node msgNode = definition.getMessageNode();
    try {
      newValue = getNewValueNode(replacement, msgNode);
    } catch (MalformedException e) {
      compiler.report(JSError.make(e.getNode(), MESSAGE_TREE_MALFORMED, e.getMessage()));
      newValue = msgNode;
    }

    if (newValue != msgNode) {
      newValue.srcrefTreeIfMissing(msgNode);
      msgNode.replaceWith(newValue);
      compiler.reportChangeToEnclosingScope(newValue);
    }
  }

  /**
   * Constructs a node representing a message's value, or, if possible, just modifies {@code
   * origValueNode} so that it accurately represents the message's value.
   *
   * @param message a message
   * @param origValueNode the message's original value node
   * @return a Node that can replace {@code origValueNode}
   * @throws MalformedException if the passed node's subtree structure is not as expected
   */
  private Node getNewValueNode(JsMessage message, Node origValueNode) throws MalformedException {
    switch (origValueNode.getToken()) {
      case FUNCTION:
        // The message is a function. Modify the function node.
        updateFunctionNode(message, origValueNode);
        return origValueNode;
      case STRINGLIT:
        // The message is a simple string. Modify the string node.
        String newString = message.toString();
        if (!origValueNode.getString().equals(newString)) {
          origValueNode.setString(newString);
          compiler.reportChangeToEnclosingScope(origValueNode);
        }
        return origValueNode;
      case ADD:
        // The message is a simple string. Create a string node.
        return IR.string(message.toString());
      case CALL:
        // The message is a function call. Replace it with a string expression.
        return replaceCallNode(message, origValueNode);
      default:
        throw new MalformedException(
            "Expected FUNCTION, STRING, or ADD node; found: " + origValueNode.getToken(),
            origValueNode);
    }
  }

  /**
   * Updates the descendants of a FUNCTION node to represent a message's value.
   *
   * <p>The tree looks something like:
   *
   * <pre>
   * function
   *  |-- name
   *  |-- lp
   *  |   |-- name <arg1>
   *  |    -- name <arg2>
   *   -- block
   *      |
   *       --return
   *           |
   *            --add
   *               |-- string foo
   *                -- name <arg1>
   * </pre>
   *
   * @param message a message
   * @param functionNode the message's original FUNCTION value node
   * @throws MalformedException if the passed node's subtree structure is not as expected
   */
  private void updateFunctionNode(JsMessage message, Node functionNode) throws MalformedException {
    checkNode(functionNode, Token.FUNCTION);
    Node nameNode = functionNode.getFirstChild();
    checkNode(nameNode, Token.NAME);
    Node argListNode = nameNode.getNext();
    checkNode(argListNode, Token.PARAM_LIST);
    Node oldBlockNode = argListNode.getNext();
    checkNode(oldBlockNode, Token.BLOCK);

    Iterator<CharSequence> iterator = message.getParts().iterator();
    Node valueNode = constructAddOrStringNode(iterator, argListNode);
    Node newBlockNode = IR.block(IR.returnNode(valueNode));

    if (!newBlockNode.isEquivalentTo(
        oldBlockNode,
        /* compareType= */ false,
        /* recurse= */ true,
        /* jsDoc= */ false,
        /* sideEffect= */ false)) {
      newBlockNode.srcrefTreeIfMissing(oldBlockNode);
      oldBlockNode.replaceWith(newBlockNode);
      compiler.reportChangeToEnclosingScope(newBlockNode);
    }
  }

  /**
   * Creates a parse tree corresponding to the remaining message parts in an iteration. The result
   * will contain only STRING nodes, NAME nodes (corresponding to placeholder references), and/or
   * ADD nodes used to combine the other two types.
   *
   * @param partsIterator an iterator over message parts
   * @param argListNode a PARAM_LIST node whose children are valid placeholder names
   * @return the root of the constructed parse tree
   * @throws MalformedException if {@code partsIterator} contains a placeholder reference that does
   *     not correspond to a valid argument in the arg list
   */
  private static Node constructAddOrStringNode(
      Iterator<CharSequence> partsIterator, Node argListNode) throws MalformedException {
    if (!partsIterator.hasNext()) {
      return IR.string("");
    }

    CharSequence part = partsIterator.next();
    Node partNode = null;
    if (part instanceof JsMessage.PlaceholderReference) {
      JsMessage.PlaceholderReference phRef = (JsMessage.PlaceholderReference) part;

      for (Node node = argListNode.getFirstChild(); node != null; node = node.getNext()) {
        if (node.isName()) {
          String arg = node.getString();

          // We ignore the case here because the transconsole only supports
          // uppercase placeholder names, but function arguments in JavaScript
          // code can have mixed case.
          if (Ascii.equalsIgnoreCase(arg, phRef.getName())) {
            partNode = IR.name(arg);
          }
        }
      }

      if (partNode == null) {
        throw new MalformedException(
            "Unrecognized message placeholder referenced: " + phRef.getName(), argListNode);
      }
    } else {
      // The part is just a string literal.
      partNode = IR.string(part.toString());
    }

    if (partsIterator.hasNext()) {
      return IR.add(partNode, constructAddOrStringNode(partsIterator, argListNode));
    } else {
      return partNode;
    }
  }

  /**
   * Replaces a CALL node with an inlined message value.
   *
   * <p>For input that that looks like this
   * <pre>
   *   goog.getMsg(
   *       'Hi {$userName}! Welcome to {$product}.',
   *       { 'userName': 'someUserName', 'product': getProductName() })
   * <pre>
   *
   * <p>We'd return:
   * <pre>
   *   'Hi ' + someUserName + '! Welcome to ' + 'getProductName()' + '.'
   * </pre>
   *
   * @param message  a message
   * @param callNode  the message's original CALL value node
   * @return a STRING node, or an ADD node that does string concatenation, if
   *   the message has one or more placeholders
   *
   * @throws MalformedException if the passed node's subtree structure is
   *   not as expected
   */
  private Node replaceCallNode(JsMessage message, Node callNode) throws MalformedException {
    checkNode(callNode, Token.CALL);
    // `goog.getMsg`
    Node getPropNode = callNode.getFirstChild();
    checkNode(getPropNode, Token.GETPROP);
    Node stringExprNode = getPropNode.getNext();
    checkStringExprNode(stringExprNode);
    // optional `{ key1: value, key2: value2 }` replacements
    Node objLitNode = stringExprNode.getNext();
    // optional replacement options, e.g. `{ html: true }`
    MsgOptions options = getOptions(objLitNode != null ? objLitNode.getNext() : null);

    Map<String, Node> placeholderMap = new HashMap<>();
    if (objLitNode != null) {
      for (Node key = objLitNode.getFirstChild(); key != null; key = key.getNext()) {
        checkState(key.isStringKey(), key);
        String name = key.getString();
        boolean isKeyAlreadySeen = placeholderMap.put(name, key.getOnlyChild()) != null;
        if (isKeyAlreadySeen) {
          throw new MalformedException("Duplicate placeholder name", key);
        }
      }
    }
    final ImmutableSet<String> placeholderNames = message.placeholders();
    if (placeholderMap.isEmpty() && !placeholderNames.isEmpty()) {
      throw new MalformedException(
          "Empty placeholder value map for a translated message with placeholders.", callNode);
    } else {
      for (String placeholderName : placeholderNames) {
        if (!placeholderMap.containsKey(placeholderName)) {
          throw new MalformedException(
              "Unrecognized message placeholder referenced: " + placeholderName, callNode);
        }
      }
    }

    // Build the replacement tree.
    return constructStringExprNode(mergeStringParts(message.getParts()), placeholderMap, options);
  }

  /**
   * Creates a parse tree corresponding to the remaining message parts in an iteration. The result
   * consists of one or more STRING nodes, placeholder replacement value nodes (which can be
   * arbitrary expressions), and ADD nodes.
   *
   * @param msgParts an iterator over message parts
   * @param placeholderMap map from placeholder names to value Nodes
   * @return the root of the constructed parse tree
   */
  private static Node constructStringExprNode(
      List<CharSequence> msgParts, Map<String, Node> placeholderMap, MsgOptions options) {

    if (msgParts.isEmpty()) {
      return IR.string("");
    } else {
      Node resultNode = null;
      for (CharSequence msgPart : msgParts) {
        final Node partNode = createNodeForMsgPart(msgPart, options, placeholderMap);
        resultNode = (resultNode == null) ? partNode : IR.add(resultNode, partNode);
      }
      return resultNode;
    }
  }

  private static Node createNodeForMsgPart(
      CharSequence part, MsgOptions options, Map<String, Node> placeholderMap) {
    final Node partNode;
    if (part instanceof JsMessage.PlaceholderReference) {
      JsMessage.PlaceholderReference phRef = (JsMessage.PlaceholderReference) part;

      final String placeholderName = phRef.getName();
      partNode = checkNotNull(placeholderMap.get(placeholderName)).cloneTree();
    } else {
      // The part is just a string literal.
      String s = part.toString();
      if (options.escapeLessThan) {
        // Note that "&" is not replaced because the translation can contain HTML entities.
        s = s.replace("<", "&lt;");
      }
      if (options.unescapeHtmlEntities) {
        // Unescape entities that need to be escaped when embedding HTML or XML in data/attributes
        // of an HTML/XML document. See https://www.w3.org/TR/xml/#sec-predefined-ent.
        // Note that "&amp;" must be the last to avoid "creating" new entities.
        // To print an html entity in the resulting message, double-escape: `&amp;amp;`.
        s =
            s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
      }
      partNode = IR.string(s);
    }
    return partNode;
  }

  private static MsgOptions getOptions(@Nullable Node optionsNode) throws MalformedException {
    MsgOptions options = new MsgOptions();
    if (optionsNode == null) {
      return options;
    }
    if (!optionsNode.isObjectLit()) {
      throw new MalformedException("OBJLIT node expected", optionsNode);
    }
    for (Node aNode = optionsNode.getFirstChild(); aNode != null; aNode = aNode.getNext()) {
      if (!aNode.isStringKey()) {
        throw new MalformedException("STRING_KEY node expected as OBJLIT key", aNode);
      }
      String optName = aNode.getString();
      Node value = aNode.getFirstChild();
      if (!value.isTrue() && !value.isFalse()) {
        throw new MalformedException("Literal true or false expected", value);
      }
      switch (optName) {
        case "html":
          options.escapeLessThan = value.isTrue();
          break;
        case "unescapeHtmlEntities":
          options.unescapeHtmlEntities = value.isTrue();
          break;
        default:
          throw new MalformedException("Unexpected option", aNode);
      }
    }
    return options;
  }

  /** Options for escaping characters in the translated messages. */
  private static class MsgOptions {
    // Replace `'<'` with `'&lt;'` in the message.
    private boolean escapeLessThan = false;
    // Replace these escaped entities with their literal characters in the message
    // (Overrides escapeLessThan)
    // '&lt;' -> '<'
    // '&gt;' -> '>'
    // '&apos;' -> "'"
    // '&quot;' -> '"'
    // '&amp;' -> '&'
    private boolean unescapeHtmlEntities = false;
  }

  /** Merges consecutive string parts in the list of message parts. */
  private static List<CharSequence> mergeStringParts(List<CharSequence> parts) {
    List<CharSequence> result = new ArrayList<>();
    for (CharSequence part : parts) {
      if (part instanceof JsMessage.PlaceholderReference) {
        result.add(part);
      } else {
        CharSequence lastPart = result.isEmpty() ? null : Iterables.getLast(result);
        if (lastPart == null || lastPart instanceof JsMessage.PlaceholderReference) {
          result.add(part);
        } else {
          result.set(result.size() - 1, lastPart.toString() + part);
        }
      }
    }
    return result;
  }

  /**
   * Checks that a node is a valid string expression (either a string literal or a concatenation of
   * string literals).
   *
   * @throws IllegalArgumentException if the node is null or the wrong type
   */
  private static void checkStringExprNode(@Nullable Node node) {
    if (node == null) {
      throw new IllegalArgumentException("Expected a string; found: null");
    }
    switch (node.getToken()) {
      case STRINGLIT:
      case TEMPLATELIT:
        break;
      case ADD:
        Node c = node.getFirstChild();
        checkStringExprNode(c);
        checkStringExprNode(c.getNext());
        break;
      default:
        throw new IllegalArgumentException("Expected a string; found: " + node.getToken());
    }
  }
}
