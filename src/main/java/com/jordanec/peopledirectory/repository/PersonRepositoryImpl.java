package com.jordanec.peopledirectory.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Polygon;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.LookupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.aggregation.UnwindOperation;
import org.springframework.data.mongodb.core.geo.GeoJsonMultiPolygon;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import com.jordanec.peopledirectory.model.Person;

import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class PersonRepositoryImpl implements PersonRepositoryCustom {

	private final MongoOperations mongoOperations;

	@Override
	public List<Person> findBornBetween(LocalDate start, LocalDate end) {
		Query query = new Query(Criteria.where("dateOfBirth").gte(start).lte(end));
		return mongoOperations.find(query, Person.class);
	}

	@Override
	public Optional<Document> findDocumentByDni(Long dni)
	{
		return Optional.ofNullable(
				mongoOperations.findOne(new Query(Criteria.where("dni").is(dni)), Document.class, "persons"));
	}

	public long getCountByCountry(String country)
	{
		Query query = new Query(Criteria.where("country").is(country));
		return mongoOperations.count(query, Person.class);
	}

	public List<Person> groupByCountry(String field, String order)
	{
		return groupDocumentByCountry(field, order).getMappedResults();
	}

	public Document groupDocumentByCountryOrdered(String field, String order)
	{
		return groupDocumentByCountry(field, order).getRawResults();
	}

	//Console equivalent:
	//db.persons.aggregate([ {$lookup: {from: "countries", localField: "country._id", foreignField: "_id", as: "country"}}, {$unwind: {path: '$country' }} ]).pretty()
	@Override
	public List<Person> lookupCountry(long dni)
	{
		MatchOperation matchOperation = Aggregation.match(Criteria.where("dni").is(dni));
		LookupOperation lookupOperation = Aggregation.lookup("countries", "country._id", "_id", "country");
		UnwindOperation unwindOperation = Aggregation.unwind("country");
		Aggregation aggregation = Aggregation.newAggregation(matchOperation, lookupOperation, unwindOperation);
		return mongoOperations.aggregate(aggregation, Person.class, Person.class).getMappedResults();
	}

	private AggregationResults<Person> groupDocumentByCountry(String field, String order)
	{
		GroupOperation groupOperation = Aggregation.group("country").count().as("total");

		SortOperation sortOperation = Aggregation
				.sort(order != null && order.toLowerCase().contains("desc") ? Sort.Direction.DESC : Sort.Direction.ASC,
						field != null && field.equalsIgnoreCase("total") ? "total" : "country");
		ProjectionOperation projectionOperation = Aggregation.project()
				.andExpression("_id").as("country")
				.andExpression("total").as("total")
				.andExclude("_id");

		Aggregation aggregation = Aggregation.newAggregation(groupOperation, projectionOperation, sortOperation);
		return mongoOperations.aggregate(aggregation, Person.class, Person.class);
	}

	public Person isOlderThan(long dni, int age)
	{
		LocalDate minimumBornDate = LocalDate.now().minusYears(age);
		MatchOperation matchOperation =
				Aggregation.match(
					Criteria.where("dni").is(dni).and("dateOfBirth").lte(minimumBornDate));
		ProjectionOperation projectionOperation =
			Aggregation.project()
				.andInclude("dni")
				.andInclude("firstName")
				.andInclude("lastName")
				.andInclude("dateOfBirth")
				.andExpression("[0] + 0", age).as("age")	//Adding 0 was needed to show it
				.andExclude("_id");
		Aggregation aggregation = Aggregation.newAggregation(matchOperation, projectionOperation);
		List<Person> personList = mongoOperations.aggregate(aggregation, Person.class, Person.class).getMappedResults();
		if (personList.isEmpty())
		{
			return null;
		}
		else
		{
			return personList.get(0);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public List<Person> delete(List<Person> persons)
	{
		for (Person person: persons)
		{
			Query query = new Query(Criteria.where("dni").is(person.getDni()));
			DeleteResult deleteResult = mongoOperations.remove(query, Person.class);
			if (deleteResult.getDeletedCount() == 0)
			{
				persons.remove(person);
			}
		}
		return persons;
//		Query query = new Query(Criteria.where("dni").in(persons.stream().map(Person::getDni).collect(Collectors.toList())));
//		return mongoOperations.findAllAndRemove(query, Person.class);
	}

	@Override
	public UpdateResult addHobbies(Person person)
	{
		Update update = new Update();
		update.set("hobbies", person.getHobbies());
		return mongoOperations.updateFirst(
			new Query(new Criteria()
				.orOperator(
					Criteria.where("dni").is(person.getDni()),
					Criteria.where("id").is(person.getId()))),
			update, Person.class);
	}

	@Override
	public UpdateResult pushHobbies(Person person)
	{
		Update update = new Update();
		update.push("hobbies").each(person.getHobbies().toArray());
		return mongoOperations.updateFirst(
			new Query(new Criteria()
				.orOperator(
					Criteria.where("dni").is(person.getDni()),
					Criteria.where("id").is(person.getId()))),
			update, Person.class);
	}

	@Override
	public UpdateResult pullHobbies(Person person)
	{
		Update update = new Update();
		update.pullAll("hobbies", person.getHobbies().toArray());
		return mongoOperations.updateFirst(
			new Query(new Criteria()
				.orOperator(
					Criteria.where("dni").is(person.getDni()),
					Criteria.where("id").is(person.getId()))),
			update, Person.class);
	}

	//db.persons.updateMany({dni: NumberLong(240703453)}, {$set: {"hobbies.$[].isSporty": true}})
	@Override
	public UpdateResult addNewFieldsToAllHobbies(Document person)
	{
		Update update = new Update();
		LinkedHashMap<String, Object> hobbiesNewFields = person.get("hobbiesNewFields", LinkedHashMap.class);
		hobbiesNewFields.forEach((name, value) -> update.set("hobbies.$[]." + name, value));
		return mongoOperations.updateMulti(
			new Query(new Criteria()
				.orOperator(
					Criteria.where("dni").is(person.get("dni")),
					Criteria.where("id").is(person.get("id")))),
			update, Person.class);
	}

	//db.persons.updateMany({dni: NumberLong(240703453)}, {$set: {"hobbies.$[hobby].goodFrequency": true}}, {arrayFilters: [{"hobby.frequency": {$gte: 2}}]})
	@Override
	public UpdateResult updateHobbiesGoodFrequency(Person person, Integer minFrequency)
	{
		//Update update = new Update().set("hobbies.$[hb].goodFrequency", true).filterArray("hb", "{\"hb.frequency\": {$gte: 2}");
		return mongoOperations.updateFirst(
			new Query(new Criteria()
				.orOperator(
					Criteria.where("dni").is(person.getDni()),
					Criteria.where("id").is(person.getId()))),
			new Update().set("hobbies.$[hb].goodFrequency", true).filterArray(Criteria.where("hb.frequency")
					.gte(minFrequency == null ? 2 : minFrequency)),
			Person.class);
	}

	/**
	 Test
	 @TODO: Implement some date operations
	 */
	public Document test()
	{
		//		ProjectionOperation projectionOperation =
		//			Aggregation.project("age", "customerId", "items")
		//                .andExpression("'$items.price' * '$items.quantity'").as("lineTotal");
		//		Criteria.where("dateBeg").lte(from).lte(to);
		return mongoOperations.aggregate(
				Aggregation.newAggregation(
						Aggregation.project("dni", "firstName", "lastName", "dateOfBirth", "email", "country")
						//					.and("dateOfBirth").minus("dateOfBirth").as("minus"));
						//					.and(DateOperators.dateFromString("06/05/2020")).minus("dateOfBirth").as("minus")
						//					.andExpression("06/05/2020 - $dateOfBirth", "dateOfBirth").as("result")
				), Person.class, Person.class).getRawResults();
	}

	@Override
	public List<Person> findByCurrentLocationWithin(GeoJsonMultiPolygon multiPolygon)
	{
		Criteria[] criterias = new Criteria[multiPolygon.getCoordinates().size()];

		for (int i = 0; i < criterias.length; i++)
		{
			GeoJsonPolygon geoJsonPolygon = multiPolygon.getCoordinates().get(i);
			Polygon polygon = new Polygon(geoJsonPolygon.getPoints());
			criterias[i] = Criteria.where("currentLocation").within(polygon);
		}

		return mongoOperations.aggregate(
			Aggregation.newAggregation(
				Aggregation.match(new Criteria().orOperator(criterias))
			)
			, Person.class, Person.class).getMappedResults();
	}

}
