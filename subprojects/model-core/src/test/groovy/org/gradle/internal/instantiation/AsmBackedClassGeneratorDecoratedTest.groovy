/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.instantiation

import com.google.common.base.Function
import org.gradle.api.Action
import org.gradle.api.NonExtensible
import org.gradle.api.internal.HasConvention
import org.gradle.api.internal.IConventionAware
import org.gradle.api.plugins.ExtensionAware
import org.gradle.internal.BiAction
import org.gradle.internal.service.DefaultServiceRegistry
import org.gradle.internal.service.ServiceLookup
import org.gradle.internal.service.ServiceRegistry
import org.gradle.internal.util.BiFunction
import org.gradle.util.ConfigureUtil
import spock.lang.Issue

import javax.inject.Inject
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

class AsmBackedClassGeneratorDecoratedTest extends AbstractClassGeneratorSpec {
    final ClassGenerator generator = AsmBackedClassGenerator.decorateAndInject()

    @Issue("GRADLE-2417")
    def "can use dynamic object as closure delegate"() {
        given:
        def thing = create(DynamicThing)

        when:
        conf(thing) {
            m1(1, 2, 3)
            p1 = 1
            p1 = p1 + 1
        }

        then:
        thing.methods.size() == 1
        thing.props.p1 == 2
    }

    def "unassociated missing exceptions are thrown"() {
        given:
        def thing1 = create(DynamicThing)

        when:
        thing1.onMethodMissing = { name, args -> [].foo() }
        conf(thing1) { m1() }

        then:
        def e = thrown(groovy.lang.MissingMethodException)
        e.method == "foo"

        when:
        thing1.onPropertyMissingGet = { new Object().bar }
        conf(thing1) { abc }

        then:
        e = thrown(groovy.lang.MissingPropertyException)
        e.property == "bar"

        when:
        thing1.onPropertyMissingSet = { name, value -> new Object().baz = true }
        conf(thing1) { abc = true }

        then:
        e = thrown(groovy.lang.MissingPropertyException)
        e.property == "baz"

    }

    def "any method with action as the last param is closurised"() {
        given:
        def tester = create(ActionsTester)

        when:
        tester.oneAction { assert it == "subject" }

        then:
        tester.lastMethod == "oneAction"
        tester.lastArgs.size() == 1
        tester.lastArgs.first() instanceof Action

        when:
        tester.twoArgs("1") { assert it == "subject" }

        then:
        tester.lastMethod == "twoArgs"
        tester.lastArgs.size() == 2
        tester.lastArgs.first() == "1"
        tester.lastArgs.last() instanceof Action

        when:
        tester.threeArgs("1", "2") { assert it == "subject" }

        then:
        tester.lastMethod == "threeArgs"
        tester.lastArgs.size() == 3
        tester.lastArgs.first() == "1"
        tester.lastArgs[1] == "2"
        tester.lastArgs.last() instanceof Action

        when:
        tester.overloaded("1") { assert it == "subject" }

        then:
        tester.lastMethod == "overloaded"
        tester.lastArgs.size() == 2
        tester.lastArgs.first() == "1"
        tester.lastArgs.last() instanceof Action

        when:
        tester.overloaded(1) { assert it == "subject" }

        then:
        tester.lastMethod == "overloaded"
        tester.lastArgs.size() == 2
        tester.lastArgs.first() == 1
        tester.lastArgs.last() instanceof Action

        when:
        def closure = { assert it == "subject" }
        tester.hasClosure("1", closure)

        then:
        tester.lastMethod == "hasClosure"
        tester.lastArgs.size() == 2
        tester.lastArgs.first() == "1"
        tester.lastArgs.last().is(closure)

        expect: // can return values
        tester.oneActionReturnsString({}) == "string"
        tester.lastArgs.last() instanceof Action
        tester.twoArgsReturnsString("foo", {}) == "string"
        tester.lastArgs.last() instanceof Action
        tester.oneActionReturnsInt({}) == 1
        tester.lastArgs.last() instanceof Action
        tester.twoArgsReturnsInt("foo", {}) == 1
        tester.lastArgs.last() instanceof Action
        tester.oneActionReturnsArray({}) == [] as Object[]
        tester.lastArgs.last() instanceof Action
        tester.twoArgsReturnsArray("foo", {}) == [] as Object[]
        tester.lastArgs.last() instanceof Action
    }

    def "property set method can take an action"() {
        given:
        def bean = create(ActionMethodWithSameNameAsProperty)
        bean.prop = "value"

        when:
        bean.prop { assert it == "value" }

        then:
        bean.prop == "called"
    }

    def "can coerce enum values"() {
        given:
        def i = create(EnumCoerceTestSubject)

        when:
        i.enumProperty = "abc"

        then:
        i.enumProperty == TestEnum.ABC

        when:
        i.someEnumMethod("DEF")

        then:
        i.enumProperty == TestEnum.DEF

        when:
        i.enumProperty "abc"

        then:
        i.enumProperty == TestEnum.ABC

        when:
        i.enumProperty "foo"

        then:
        thrown IllegalArgumentException

        when:
        i.enumMethodWithStringOverload("foo")

        then:
        i.stringValue == "foo"

        when:
        i.enumMethodWithStringOverload(TestEnum.DEF)

        then:
        i.enumProperty == TestEnum.DEF
    }

    def "can call methods during construction"() {
        /*
            We route all methods through invokeMethod, which requires fields
            added in the subclass. We have special handling for the case where
            methods are called before this field has been initialised; this tests that.
         */
        when:
        def i = create(CallsMethodDuringConstruction)

        then:
        i.setDuringConstructor == i.class
        i.setAtFieldInit == i.class
    }

    def "can use inherited properties during construction"() {
        when:
        def i = create(UsesInheritedPropertiesDuringConstruction)

        then:
        i.someValue == 'value'
    }

    def "can call private methods internally"() {
        /*
            We have to specially handle private methods in our dynamic protocol.
         */
        given:
        def i = create(CallsPrivateMethods)

        when:
        i.flagCalled("a")

        then:
        i.calledWith == String

        when:
        i.flagCalled(1.2)

        then:
        i.calledWith == Number

        when:
        i.flagCalled([])

        then:
        i.calledWith == Object

        when:
        i.flagCalled(1)

        then:
        i.calledWith == Integer
    }

    def "can use non extensible objects"() {
        def i = create(NonExtensibleObject)

        when:
        i.testEnum "ABC"

        then:
        i.testEnum == TestEnum.ABC

        !(TestEnum instanceof ExtensionAware)
        !(TestEnum instanceof IConventionAware)
        !(TestEnum instanceof HasConvention)

        when:
        i.ext.foo = "bar"

        then:
        def e = thrown(MissingPropertyException)
        e.property == "ext"
    }

    def conf(o, c) {
        ConfigureUtil.configure(c, o)
    }

    @Issue("https://issues.gradle.org/browse/GRADLE-2863")
    def "checked exceptions from private methods are thrown"() {
        when:
        create(CallsPrivateMethods).callsPrivateThatThrowsCheckedException("1")

        then:
        thrown IOException
    }

    @Issue("https://issues.gradle.org/browse/GRADLE-2863")
    def "private methods are called with Groovy semantics"() {
        when:
        def foo = "bar"
        def obj = create(CallsPrivateMethods)

        then:
        obj.callsPrivateStringMethodWithGString("$foo") == "BAR"
    }

    def "class can implement interface methods using Groovy property"() {
        when:
        def i = create(ImplementsInterface)
        i.prop = "prop"

        then:
        i.prop == "prop"
    }

    def "can inject service using @Inject on a getter method with dummy method body"() {
        given:
        def services = Mock(ServiceLookup)
        _ * services.get(Number) >> 12

        when:
        def obj = create(BeanWithServices, services)

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12
    }

    def "can inject service using @Inject on an abstract service getter method"() {
        given:
        def services = Mock(ServiceLookup)
        _ * services.get(Number) >> 12

        when:
        def obj = create(AbstractBeanWithServices, services)

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12
    }

    def "can optionally set injected service using a service setter method"() {
        given:
        def services = Mock(ServiceLookup)

        when:
        def obj = create(BeanWithMutableServices, services)
        obj.thing = 12

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12

        and:
        0 * services._
    }

    def "service lookup is lazy and the result is cached"() {
        given:
        def services = Mock(ServiceLookup)

        when:
        def obj = create(BeanWithServices, services)

        then:
        0 * services._

        when:
        obj.thing

        then:
        1 * services.get(Number) >> 12
        0 * services._

        when:
        obj.thing

        then:
        0 * services._
    }

    def "can inject service using a custom annotation on getter method with dummy method body"() {
        given:
        def services = Mock(ServiceLookup)
        _ * services.get(Number) >> 12

        when:
        def obj = create(AsmBackedClassGenerator.injectOnly([CustomInject] as Set), BeanWithCustomServices, services)

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12
    }

    def "can inject service using a custom annotation on abstract getter method"() {
        given:
        def services = Mock(ServiceLookup)
        _ * services.get(Number) >> 12

        when:
        def obj = create(AsmBackedClassGenerator.injectOnly([CustomInject] as Set), AbstractBeanWithCustomServices, services)

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12
    }

    def "object can provide its own service registry to provide services for injection"() {
        given:
        def services = Mock(ServiceLookup)

        when:
        def obj = create(BeanWithServicesAndServiceRegistry, services)

        then:
        obj.thing == 12
        obj.getThing() == 12
        obj.getProperty("thing") == 12
    }

    def "property missing implementation is invoked exactly once, with actual value"() {
        given:
        def thing = create(DynamicThing)
        def values = []
        thing.onPropertyMissingSet = { n, v -> values << v }

        when:
        thing.foo = "bar"

        then:
        values == ["bar"]
    }
}

enum TestEnum {
    ABC, DEF
}

class EnumCoerceTestSubject {
    TestEnum enumProperty

    String stringValue

    void someEnumMethod(TestEnum testEnum) {
        this.enumProperty = testEnum
    }

    void enumMethodWithStringOverload(TestEnum testEnum) {
        enumProperty = testEnum
    }

    void enumMethodWithStringOverload(String stringValue) {
        this.stringValue = stringValue
    }
}

@NonExtensible
class NonExtensibleObject {
    TestEnum testEnum
}

class DynamicThing {
    def methods = [:]
    def props = [:]

    BiFunction<Object, String, Object[]> onMethodMissing = { name, args -> methods[name] = args.toList(); null }
    Function<String, Object> onPropertyMissingGet = { name -> props[name] }
    BiAction<String, Object> onPropertyMissingSet = { name, value -> props[name] = value }

    def methodMissing(String name, args) {
        onMethodMissing.apply(name, args as Object[])
    }

    def propertyMissing(String name) {
        onPropertyMissingGet.apply(name)
    }

    def propertyMissing(String name, value) {
        onPropertyMissingSet.execute(name, value)
    }
}

class ActionsTester {

    Object subject = "subject"
    String lastMethod
    List lastArgs

    void oneAction(Action action) {
        lastMethod = "oneAction"
        lastArgs = [action]
        action.execute(subject)
    }

    void twoArgs(String first, Action action) {
        lastMethod = "twoArgs"
        lastArgs = [first, action]
        action.execute(subject)
    }

    void threeArgs(String first, String second, Action action) {
        lastMethod = "threeArgs"
        lastArgs = [first, second, action]
        action.execute(subject)
    }

    void overloaded(Integer i, Action action) {
        lastMethod = "overloaded"
        lastArgs = [i, action]
        action.execute(subject)
    }

    void overloaded(String s, Action action) {
        lastMethod = "overloaded"
        lastArgs = [s, action]
        action.execute(subject)
    }

    void hasClosure(String s, Action action) {
        lastMethod = "hasClosure"
        lastArgs = [s, action]
    }

    void hasClosure(String s, Closure closure) {
        lastMethod = "hasClosure"
        lastArgs = [s, closure]
    }

    String oneActionReturnsString(Action action) {
        lastMethod = "oneAction"
        lastArgs = [action]
        action.execute(subject)
        "string"
    }

    String twoArgsReturnsString(String first, Action action) {
        lastMethod = "twoArgs"
        lastArgs = [first, action]
        action.execute(subject)
        "string"
    }

    int oneActionReturnsInt(Action action) {
        lastMethod = "oneAction"
        lastArgs = [action]
        action.execute(subject)
        1
    }

    int twoArgsReturnsInt(String first, Action action) {
        lastMethod = "twoArgs"
        lastArgs = [first, action]
        action.execute(subject)
        1
    }

    Object[] oneActionReturnsArray(Action action) {
        lastMethod = "oneAction"
        lastArgs = [action]
        action.execute(subject)
        [] as Object[]
    }

    Object[] twoArgsReturnsArray(String first, Action action) {
        lastMethod = "twoArgs"
        lastArgs = [first, action]
        action.execute(subject)
        [] as Object[]
    }

}

class ActionMethodWithSameNameAsProperty {
    String prop
    void prop(Action<String> action) {
        action.execute(prop)
        prop = "called"
    }
}

class CallsMethodDuringConstruction {

    Class setAtFieldInit = getClass()
    Map<String, String> someMap = [:]
    Class setDuringConstructor

    CallsMethodDuringConstruction() {
        setDuringConstructor = setAtFieldInit
        someMap['a'] = 'b'
        assert setDuringConstructor
    }
}

class UsesInheritedPropertiesDuringConstruction extends TestJavaObject {
    UsesInheritedPropertiesDuringConstruction() {
        assert metaClass != null
        assert getMetaClass() != null
        assert metaClass.getProperty(this, "someValue") == "value"
        assert asDynamicObject.getProperty("someValue") == "value"
        assert getProperty("someValue") == "value"
        assert someValue == "value"
    }
}

class CallsPrivateMethods {

    Class calledWith

    void flagCalled(arg) {
        doFlagCalled(arg)
    }

    private doFlagCalled(String s) {
        calledWith = String
    }

    private doFlagCalled(Number s) {
        calledWith = Number
    }

    private doFlagCalled(Integer s) {
        calledWith = Integer
    }

    private doFlagCalled(Object s) {
        calledWith = Object
    }

    // It's important here that we take an untyped arg, and call a method that types a typed arg
    // See https://issues.gradle.org/browse/GRADLE-2863
    def callsPrivateThatThrowsCheckedException(s) {
        try {
            throwsCheckedException(s)
        } catch (Exception e) {
            assert e instanceof IOException
            throw e
        }
    }

    private throwsCheckedException(String a) {
        throw new IOException("!")
    }

    def callsPrivateStringMethodWithGString(GString gString) {
        upperCaser(gString)
    }

    private upperCaser(String str) {
        str.toUpperCase()
    }
}

abstract class AbstractBeanWithServices {
    @Inject
    abstract Number getThing()
}

class BeanWithServices {
    @Inject
    Number getThing() { throw new UnsupportedOperationException() }
}

class BeanWithMutableServices extends BeanWithServices {
    void setThing(Number number) { throw new UnsupportedOperationException() }
}

class BeanWithServicesAndServiceRegistry extends BeanWithServices {
    ServiceRegistry getServices() {
        def services = new DefaultServiceRegistry()
        services.add(Number, 12)
        return services
    }
}

interface WithProperties {
    String getProp()
}

class ImplementsInterface implements WithProperties {
    String prop
}

@Retention(RetentionPolicy.RUNTIME)
@interface CustomInject {
}

class BeanWithCustomServices {
    @CustomInject
    Number getThing() { throw new UnsupportedOperationException() }
}

abstract class AbstractBeanWithCustomServices {
    @CustomInject
    abstract Number getThing()
}