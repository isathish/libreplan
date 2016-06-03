/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2010-2011 Igalia, S.L.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.libreplan.business.common;

import java.beans.Introspector;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.AssertionFailure;
import org.hibernate.Hibernate;
import org.hibernate.MappingException;
import org.hibernate.annotations.common.reflection.Filter;
import org.hibernate.annotations.common.reflection.ReflectionManager;
import org.hibernate.annotations.common.reflection.XClass;
import org.hibernate.annotations.common.reflection.XMember;
import org.hibernate.annotations.common.reflection.XMethod;
import org.hibernate.annotations.common.reflection.XProperty;
import org.hibernate.annotations.common.reflection.java.JavaReflectionManager;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.util.IdentitySet;
import org.hibernate.validator.InvalidStateException;
import org.hibernate.validator.InvalidValue;
import org.hibernate.validator.MessageInterpolator;
import org.hibernate.validator.PersistentClassConstraint;
import org.hibernate.validator.PropertyConstraint;
import org.hibernate.validator.Valid;
import org.hibernate.validator.Validator;
import org.hibernate.validator.ValidatorClass;
import org.hibernate.validator.Version;
import org.hibernate.validator.interpolator.DefaultMessageInterpolatorAggerator;


/**
 * This class is copy-cat of HibernateValidator.ClassValidator
 *
 * Adds extra functionality to cache extra ClassValidators created for validating children elements
 * (see getClassValidator)
 *
 * The function createChildValidator creates ClassValidators for getters and members marked for Validation,
 * but it doesn't create ClassValidators for members that return Collections or ArrayList of entities.
 * These ClassValidators are created later in getClassValidator but are not cached.
 *
 * Original code: http://anonsvn.jboss.org/repos/hibernate/validator/trunk/hibernate-validator-legacy/src/main/java/org/hibernate/validator/ClassValidator.java
 *
 * @author Diego Pino <dpino@igalia.com>
 */
public class LibrePlanClassValidator<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private static Log log = LogFactory.getLog( LibrePlanClassValidator.class );
    private static final InvalidValue[] EMPTY_INVALID_VALUE_ARRAY = new InvalidValue[]{};

    private static final String DEFAULT_VALIDATOR_MESSAGE =
            "org.hibernate.validator.resources.DefaultValidatorMessages";

    private static final String VALIDATOR_MESSAGE = "ValidatorMessages";
    private static final Set<Class> INDEXABLE_CLASS = new HashSet<Class>();

    static {
        INDEXABLE_CLASS.add( Integer.class );
        INDEXABLE_CLASS.add( Long.class );
        INDEXABLE_CLASS.add( String.class );
    }

    static {
        Version.touch(); //touch version
    }

    private final Class<T> beanClass;
    private transient ResourceBundle messageBundle;
    private transient ResourceBundle defaultMessageBundle;
    private transient boolean isUserProvidedResourceBundle;
    private transient ReflectionManager reflectionManager;

    private final transient Map<XClass, LibrePlanClassValidator> childClassValidators;
    private final transient Map<XClass, LibrePlanClassValidator> extraClassValidators;
    private transient List<Validator> beanValidators;
    private transient List<Validator> memberValidators;
    private transient List<XMember> memberGetters;
    private transient List<XMember> childGetters;
    private transient DefaultMessageInterpolatorAggerator defaultInterpolator;
    private transient MessageInterpolator userInterpolator;

    private static final Filter GET_ALL_FILTER = new Filter() {
        public boolean returnStatic() {
        return true;
        }

        public boolean returnTransient() {
        return true;
        }
    };

    /**
     * create the validator engine for this bean type
     */
    public LibrePlanClassValidator(Class<T> beanClass) {
        this( beanClass, (ResourceBundle) null );
    }

    /**
     * create the validator engine for a particular bean class, using a resource bundle
     * for message rendering on violation
     */
    public LibrePlanClassValidator(Class<T> beanClass, ResourceBundle resourceBundle) {
        this( beanClass, resourceBundle, null, new HashMap<XClass, LibrePlanClassValidator>(), null );
    }

    /**
     * create the validator engine for a particular bean class, using a custom message interpolator
     * for message rendering on violation
     */
    public LibrePlanClassValidator(Class<T> beanClass, MessageInterpolator interpolator) {
        this( beanClass, null, interpolator, new HashMap<XClass, LibrePlanClassValidator>(), null );
    }

    /**
     * Not a public API
     */
    public LibrePlanClassValidator(Class<T> beanClass,
                                   ResourceBundle resourceBundle,
                                   MessageInterpolator interpolator,
                                   Map<XClass, LibrePlanClassValidator> childClassValidators,
                                   ReflectionManager reflectionManager) {

        this.reflectionManager = reflectionManager != null ? reflectionManager : new JavaReflectionManager();
        XClass beanXClass = this.reflectionManager.toXClass( beanClass );
        this.beanClass = beanClass;

        this.messageBundle = resourceBundle == null ? getDefaultResourceBundle() : resourceBundle;

        this.defaultMessageBundle = ResourceBundle.getBundle( DEFAULT_VALIDATOR_MESSAGE );
        this.userInterpolator = interpolator;

        this.childClassValidators =
                childClassValidators != null ? childClassValidators : new HashMap<XClass, LibrePlanClassValidator>();

        this.extraClassValidators = new HashMap<XClass, LibrePlanClassValidator>();
        initValidator( beanXClass, this.childClassValidators );
    }

    @SuppressWarnings("unchecked")
    protected LibrePlanClassValidator(XClass beanXClass,
                                      ResourceBundle resourceBundle,
                                      MessageInterpolator userInterpolator,
                                      Map<XClass, LibrePlanClassValidator> childClassValidators,
                                      ReflectionManager reflectionManager) {

        this.reflectionManager = reflectionManager;
        this.beanClass = reflectionManager.toClass( beanXClass );
        this.messageBundle = resourceBundle == null ? getDefaultResourceBundle() : resourceBundle;
        this.defaultMessageBundle = ResourceBundle.getBundle( DEFAULT_VALIDATOR_MESSAGE );
        this.userInterpolator = userInterpolator;
        this.childClassValidators = childClassValidators;
        this.extraClassValidators = new HashMap<XClass, LibrePlanClassValidator>();
        initValidator( beanXClass, childClassValidators );
    }

    private ResourceBundle getDefaultResourceBundle() {
        ResourceBundle rb;
        try {
            // Use context class loader as a first citizen
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if ( contextClassLoader == null ) {
                throw new MissingResourceException( "No context classloader", null, VALIDATOR_MESSAGE );
            }

            rb = ResourceBundle.getBundle(
                    VALIDATOR_MESSAGE,
                    Locale.getDefault(),
                    contextClassLoader
            );
        }
        catch (MissingResourceException e) {
            log.trace( "ResourceBundle " + VALIDATOR_MESSAGE + " not found in thread context classloader" );
            // Then use the Validator Framework classloader
            try {
                rb = ResourceBundle.getBundle(
                        VALIDATOR_MESSAGE,
                        Locale.getDefault(),
                        this.getClass().getClassLoader()
                );
            }
            catch (MissingResourceException ee) {
                log.debug(
                        "ResourceBundle ValidatorMessages not found in Validator classloader. Delegate to " +
                        DEFAULT_VALIDATOR_MESSAGE);

                // The user did not override the default ValidatorMessages
                rb = null;
            }
        }
        isUserProvidedResourceBundle = true;

        return rb;
    }

    private void initValidator(XClass xClass, Map<XClass, LibrePlanClassValidator> childClassValidators) {
        beanValidators = new ArrayList<Validator>();
        memberValidators = new ArrayList<Validator>();
        memberGetters = new ArrayList<XMember>();
        childGetters = new ArrayList<XMember>();
        defaultInterpolator = new DefaultMessageInterpolatorAggerator();
        defaultInterpolator.initialize( messageBundle, defaultMessageBundle );

        // Build the class hierarchy to look for members in
        childClassValidators.put( xClass, this );
        Collection<XClass> classes = new HashSet<XClass>();
        addSuperClassesAndInterfaces( xClass, classes );

        for ( XClass currentClass : classes ) {
            Annotation[] classAnnotations = currentClass.getAnnotations();

            for (Annotation classAnnotation : classAnnotations) {
                Validator beanValidator = createValidator(classAnnotation);

                if ( beanValidator != null )
                    beanValidators.add(beanValidator);

                handleAggregateAnnotations(classAnnotation, null);
            }
        }

        // Check on all selected classes
        for ( XClass currClass : classes ) {
            List<XMethod> methods = currClass.getDeclaredMethods();
            for ( XMethod method : methods ) {
                createMemberValidator( method );
                createChildValidator( method );
            }

            List<XProperty> fields = currClass.getDeclaredProperties("field", GET_ALL_FILTER
            );
            for ( XProperty field : fields ) {
                createMemberValidator( field );
                createChildValidator( field );
            }
        }
    }

    private void addSuperClassesAndInterfaces(XClass clazz, Collection<XClass> classes) {
        for ( XClass currClass = clazz; currClass != null ; currClass = currClass.getSuperclass() ) {

            if ( ! classes.add( currClass ) )
                return;

            XClass[] interfaces = currClass.getInterfaces();
            for ( XClass interf : interfaces ) {
                addSuperClassesAndInterfaces( interf, classes );
            }
        }
    }

    private boolean handleAggregateAnnotations(Annotation annotation, XMember member) {
        Object[] values;
        try {
            Method valueMethod = annotation.getClass().getMethod( "value" );
            if ( valueMethod.getReturnType().isArray() ) {
                values = (Object[]) valueMethod.invoke( annotation );
            }
            else {
                return false;
            }
        }
        catch (NoSuchMethodException e) {
            return false;
        }
        catch (Exception e) {
            throw new IllegalStateException( e );
        }

        boolean validatorPresent = false;
        for ( Object value : values ) {
            if ( value instanceof Annotation ) {
                annotation = (Annotation) value;
                Validator validator = createValidator( annotation );
                if ( validator != null ) {
                    if ( member != null ) {
                        // Member
                        memberValidators.add( validator );
                        setAccessible( member );
                        memberGetters.add( member );
                    }
                    else {
                        // Bean
                        beanValidators.add( validator );
                    }
                    validatorPresent = true;
                }
            }
        }
        return validatorPresent;
    }

    @SuppressWarnings("unchecked")
    private void createChildValidator( XMember member) {
        if ( member.isAnnotationPresent( Valid.class ) ) {
            setAccessible( member );
            childGetters.add( member );
            XClass clazz;

            if ( member.isCollection() || member.isArray() ) {
                clazz = member.getElementClass();
            }
            else {
                clazz = member.getType();
            }
            if ( !childClassValidators.containsKey( clazz ) ) {
                // ClassValidator added by side effect (added to childClassValidators during CV construction)
                new LibrePlanClassValidator(
                        clazz, messageBundle, userInterpolator, childClassValidators, reflectionManager);
            }
        }
    }

    private void createMemberValidator(XMember member) {
        boolean validatorPresent = false;
        Annotation[] memberAnnotations = member.getAnnotations();

        for ( Annotation methodAnnotation : memberAnnotations ) {
            Validator propertyValidator = createValidator( methodAnnotation );

            if ( propertyValidator != null ) {
                memberValidators.add( propertyValidator );
                setAccessible( member );
                memberGetters.add( member );
                validatorPresent = true;
            }
            boolean agrValidPresent = handleAggregateAnnotations( methodAnnotation, member );
            validatorPresent = validatorPresent || agrValidPresent;
        }
        if ( validatorPresent && !member.isTypeResolved() ) {
            log.warn( "Original type of property " + member + " is unbound and has been approximated." );
        }
    }

    private static void setAccessible(XMember member) {
        if ( !Modifier.isPublic( member.getModifiers() ) ) {
            member.setAccessible( true );
        }
    }

    @SuppressWarnings("unchecked")
    private Validator createValidator(Annotation annotation) {
        try {
            ValidatorClass validatorClass = annotation.annotationType().getAnnotation( ValidatorClass.class );
            if ( validatorClass == null ) {
                return null;
            }
            Validator beanValidator = validatorClass.value().newInstance();
            beanValidator.initialize( annotation );
            defaultInterpolator.addInterpolator( annotation, beanValidator );

            return beanValidator;
        }
        catch (Exception e) {
            throw new IllegalArgumentException( "could not instantiate LibrePlanClassValidator", e );
        }
    }

    public boolean hasValidationRules() {
        return beanValidators.size() != 0 || memberValidators.size() != 0;
    }

    /**
     * Apply constraints on a bean instance and return all the failures.
     * If <code>bean</code> is null, an empty array is returned
     */
    public InvalidValue[] getInvalidValues(T bean) {
        return this.getInvalidValues( bean, new IdentitySet() );
    }

    /**
     * Apply constraints on a bean instance and return all the failures.
     * If <code>bean</code> is null, an empty array is returned
     */
    @SuppressWarnings("unchecked")
    protected InvalidValue[] getInvalidValues(T bean, Set<Object> circularityState) {
        if ( bean == null || circularityState.contains( bean ) ) {
            // Avoid circularity
            return EMPTY_INVALID_VALUE_ARRAY;
        }
        else {
            circularityState.add( bean );
        }

        if ( !beanClass.isInstance( bean ) ) {
            throw new IllegalArgumentException( "not an instance of: " + bean.getClass() );
        }

        List<InvalidValue> results = new ArrayList<InvalidValue>();

        for (Validator validator : beanValidators) {
            if ( !validator.isValid(bean) ) {
                results.add(new InvalidValue(interpolate(validator), beanClass, null, bean, bean));
            }
        }

        for ( int i = 0; i < memberValidators.size() ; i++ ) {
            XMember getter = memberGetters.get( i );

            if ( Hibernate.isPropertyInitialized( bean, getPropertyName( getter ) ) ) {
                Object value = getMemberValue( bean, getter );
                Validator validator = memberValidators.get( i );

                if ( !validator.isValid( value ) ) {
                    String propertyName = getPropertyName( getter );
                    results.add( new InvalidValue( interpolate(validator), beanClass, propertyName, value, bean ) );
                }
            }
        }

        for (XMember getter : childGetters) {

            if ( Hibernate.isPropertyInitialized(bean, getPropertyName(getter)) ) {

                Object value = getMemberValue(bean, getter);
                if ( value != null && Hibernate.isInitialized(value) ) {

                    String propertyName = getPropertyName(getter);
                    if ( getter.isCollection() ) {

                        int index = 0;
                        boolean isIterable = value instanceof Iterable;
                        Map map = !isIterable ? (Map) value : null;
                        Iterable elements = isIterable ? (Iterable) value : map.keySet();

                        for (Object element : elements) {
                            Object actualElement = isIterable ? element : map.get(element);

                            if ( actualElement == null ) {
                                index++;
                                continue;
                            }
                            InvalidValue[] invalidValues =
                                    getClassValidator(actualElement).getInvalidValues(actualElement, circularityState);

                            String indexedPropName = MessageFormat.format(
                                    "{0}[{1}]",
                                    propertyName,
                                    INDEXABLE_CLASS.contains(element.getClass()) ? ("'" + element + "'") : index);

                            index++;

                            for (InvalidValue invalidValue : invalidValues) {
                                invalidValue.addParentBean(bean, indexedPropName);
                                results.add(invalidValue);
                            }
                        }
                    }
                    if ( getter.isArray() ) {
                        int index = 0;
                        for (Object element : (Object[]) value) {
                            if ( element == null ) {
                                index++;
                                continue;
                            }
                            InvalidValue[] invalidValues =
                                    getClassValidator(element).getInvalidValues(element, circularityState);

                            String indexedPropName = MessageFormat.format("{0}[{1}]", propertyName, index);
                            index++;

                            for (InvalidValue invalidValue : invalidValues) {
                                invalidValue.addParentBean(bean, indexedPropName);
                                results.add(invalidValue);
                            }
                        }
                    } else {

                        InvalidValue[] invalidValues =
                                getClassValidator(value).getInvalidValues(value, circularityState);

                        for (InvalidValue invalidValue : invalidValues) {
                            invalidValue.addParentBean(bean, propertyName);
                            results.add(invalidValue);
                        }
                    }
                }
            }
        }

        return results.toArray( new InvalidValue[results.size()] );
    }

    private String interpolate(Validator validator) {
        String message = defaultInterpolator.getAnnotationMessage( validator );
        if ( userInterpolator != null ) {
            return userInterpolator.interpolate( message, validator, defaultInterpolator );
        }
        else {
            return defaultInterpolator.interpolate( message, validator, null);
        }
    }

    @SuppressWarnings("unchecked")
    private LibrePlanClassValidator getClassValidator(Object value) {
        Class clazz = value.getClass();
        XClass xclass = reflectionManager.toXClass( clazz );
        LibrePlanClassValidator validator = childClassValidators.get( xclass );

        // Handles polymorphism
        if ( validator == null ) {
            validator = extraClassValidators.get( xclass );
            if ( validator == null ) {
                validator = new LibrePlanClassValidator( clazz );
                extraClassValidators.put( xclass, validator );
            }
        }
        return validator;
    }

    /**
     * Apply constraints of a particular property on a bean instance and return all the failures.
     * Note this is not recursive.
     */
    //TODO should it be recursive?
    public InvalidValue[] getInvalidValues(T bean, String propertyName) {
        List<InvalidValue> results = new ArrayList<InvalidValue>();

        for ( int i = 0; i < memberValidators.size() ; i++ ) {
            XMember getter = memberGetters.get( i );
            if ( getPropertyName( getter ).equals( propertyName ) ) {
                Object value = getMemberValue( bean, getter );
                Validator validator = memberValidators.get( i );
                if ( !validator.isValid( value ) ) {
                    results.add( new InvalidValue( interpolate(validator), beanClass, propertyName, value, bean ) );
                }
            }
        }

        return results.toArray( new InvalidValue[results.size()] );
    }

    /**
     * Apply constraints of a particular property value of a bean type and return all the failures.
     * The InvalidValue objects returns return null for InvalidValue#getBean() and InvalidValue#getRootBean()
     * Note this is not recursive.
     */
    //TODO should it be recursive?
    public InvalidValue[] getPotentialInvalidValues(String propertyName, Object value) {
        List<InvalidValue> results = new ArrayList<InvalidValue>();

        for ( int i = 0; i < memberValidators.size() ; i++ ) {
            XMember getter = memberGetters.get( i );
            if ( getPropertyName( getter ).equals( propertyName ) ) {
                Validator validator = memberValidators.get( i );
                if ( !validator.isValid( value ) ) {
                    results.add( new InvalidValue( interpolate(validator), beanClass, propertyName, value, null ) );
                }
            }
        }

        return results.toArray( new InvalidValue[results.size()] );
    }

    private Object getMemberValue(T bean, XMember getter) {
        Object value;
        try {
            value = getter.invoke( bean );
        }
        catch (Exception e) {
            throw new IllegalStateException( "Could not get property value", e );
        }
        return value;
    }

    public String getPropertyName(XMember member) {
        // Do no try to cache the result in a map, it is actually much slower (2.x time)
        String propertyName;
        if ( XProperty.class.isAssignableFrom( member.getClass() ) ) {
            propertyName = member.getName();
        }
        else if ( XMethod.class.isAssignableFrom( member.getClass() ) ) {
            propertyName = member.getName();
            if ( propertyName.startsWith( "is" ) ) {
                propertyName = Introspector.decapitalize( propertyName.substring( 2 ) );
            }
            else if ( propertyName.startsWith( "get" ) ) {
                propertyName = Introspector.decapitalize( propertyName.substring( 3 ) );
            }
            // Do nothing for non getter method, in case someone want to validate a PO Method
        }
        else {
            throw new AssertionFailure( "Unexpected member: " + member.getClass().getName() );
        }
        return propertyName;
    }

    /**
     * Apply the registered constraints rules on the hibernate metadata (to be applied on DB schema...)
     *
     * @param persistentClass hibernate metadata
     */
    public void apply(PersistentClass persistentClass) {

        for ( Validator validator : beanValidators ) {
            if ( validator instanceof PersistentClassConstraint ) {
                ( (PersistentClassConstraint) validator ).apply( persistentClass );
            }
        }

        Iterator<Validator> validators = memberValidators.iterator();
        Iterator<XMember> getters = memberGetters.iterator();
        while ( validators.hasNext() ) {
            Validator validator = validators.next();
            String propertyName = getPropertyName( getters.next() );
            if ( validator instanceof PropertyConstraint ) {
                try {
                    Property property = findPropertyByName(persistentClass, propertyName);
                    if ( property != null ) {
                        ( (PropertyConstraint) validator ).apply( property );
                    }
                }
                catch (MappingException pnfe) {
                    // Do nothing
                }
            }
        }

    }

    public void assertValid(T bean) {
        InvalidValue[] values = getInvalidValues( bean );
        if ( values.length > 0 ) {
            throw new InvalidStateException( values );
        }
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        ResourceBundle rb = messageBundle;
        MessageInterpolator interpolator = this.userInterpolator;
        if ( rb != null && ! ( rb instanceof Serializable ) ) {
            messageBundle = null;
            if ( ! isUserProvidedResourceBundle ) {
                log.warn(
                        "Serializing a LibrePlanClassValidator with a non serializable ResourceBundle:" +
                                " ResourceBundle ignored");
            }
        }
        if ( interpolator != null && ! (interpolator instanceof Serializable) ) {
            userInterpolator = null;
            log.warn( "Serializing a non serializable MessageInterpolator" );
        }
        oos.defaultWriteObject();
        oos.writeObject( messageBundle );
        oos.writeObject( userInterpolator );
        messageBundle = rb;
        userInterpolator = interpolator;
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        ResourceBundle rb = (ResourceBundle) ois.readObject();

        if ( rb == null )
            rb = getDefaultResourceBundle();

        this.messageBundle = rb;
        this.userInterpolator = (MessageInterpolator) ois.readObject();
        this.defaultMessageBundle = ResourceBundle.getBundle( DEFAULT_VALIDATOR_MESSAGE );
        reflectionManager = new JavaReflectionManager();
        initValidator( reflectionManager.toXClass( beanClass ), new HashMap<XClass, LibrePlanClassValidator>() );
    }

    /**
     * Retrieve the property by path in a recursive way, including IndetifierProperty in the loop.
     * If propertyName is null or empty, the IdentifierProperty is returned.
     */
    public static Property findPropertyByName(PersistentClass associatedClass, String propertyName) {
        Property property = null;
        Property idProperty = associatedClass.getIdentifierProperty();
        String idName = idProperty != null ? idProperty.getName() : null;
        try {
            if ( propertyName == null || propertyName.length() == 0 || propertyName.equals( idName ) ) {

                // Default to id
                property = idProperty;
            }
            else {
                if ( propertyName.indexOf( idName + "." ) == 0 ) {
                    property = idProperty;
                    propertyName = propertyName.substring( idName.length() + 1 );
                }
                StringTokenizer st = new StringTokenizer( propertyName, ".", false );
                while ( st.hasMoreElements() ) {
                    String element = (String) st.nextElement();
                    if ( property == null ) {
                        property = associatedClass.getProperty( element );
                    }
                    else {
                        if ( ! property.isComposite() )
                            return null;

                        property = ( (Component) property.getValue() ).getProperty( element );
                    }
                }
            }
        }
        catch (MappingException e) {
            try {
                // If we do not find it try to check the identifier mapper
                if ( associatedClass.getIdentifierMapper() == null )
                    return null;

                StringTokenizer st = new StringTokenizer( propertyName, ".", false );
                while ( st.hasMoreElements() ) {
                    String element = (String) st.nextElement();
                    if ( property == null ) {
                        property = associatedClass.getIdentifierMapper().getProperty( element );
                    }
                    else {
                        if ( ! property.isComposite() )
                            return null;
                        property = ( (Component) property.getValue() ).getProperty( element );
                    }
                }
            }
            catch (MappingException ee) {
                return null;
            }
        }
        return property;
    }
}
