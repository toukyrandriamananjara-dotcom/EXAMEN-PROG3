package main.java;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class DataRetriever {

    public Order findOrderByReference(String reference) {
        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("""
                select o.id, o.reference, o.creation_datetime, o.payment_status, o.id_sale,
                       s.creation_datetime as sale_creation_datetime
                from "order" o
                left join sale s on o.id_sale = s.id
                where o.reference like ?""");
            preparedStatement.setString(1, reference);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Order order = new Order();
                Integer idOrder = resultSet.getInt("id");
                order.setId(idOrder);
                order.setReference(resultSet.getString("reference"));
                order.setCreationDatetime(resultSet.getTimestamp("creation_datetime").toInstant());
                order.setPaymentStatus(PaymentStatusEnum.valueOf(resultSet.getString("payment_status")));

                Integer saleId = resultSet.getObject("id_sale") != null ?
                        resultSet.getInt("id_sale") : null;
                if (saleId != null) {
                    Sale sale = new Sale();
                    sale.setId(saleId);
                    sale.setCreationDatetime(resultSet.getTimestamp("sale_creation_datetime").toInstant());
                    sale.setOrder(order);
                    order.setSale(sale);
                }

                order.setDishOrders(findDishOrderByIdOrder(idOrder));
                return order;
            }
            throw new RuntimeException("Order not found with reference " + reference);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<DishOrder> findDishOrderByIdOrder(Integer idOrder) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<DishOrder> dishOrders = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select id, id_dish, quantity from dish_order where dish_order.id_order = ?
                            """);
            preparedStatement.setInt(1, idOrder);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Dish dish = findDishById(resultSet.getInt("id_dish"));
                DishOrder dishOrder = new DishOrder();
                dishOrder.setId(resultSet.getInt("id"));
                dishOrder.setQuantity(resultSet.getInt("quantity"));
                dishOrder.setDish(dish);
                dishOrders.add(dishOrder);
            }
            dbConnection.closeConnection(connection);
            return dishOrders;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    Dish findDishById(Integer id) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select dish.id as dish_id, dish.name as dish_name, dish_type, dish.selling_price as dish_price
                            from dish
                            where dish.id = ?;
                            """);
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Dish dish = new Dish();
                dish.setId(resultSet.getInt("dish_id"));
                dish.setName(resultSet.getString("dish_name"));
                dish.setDishType(DishTypeEnum.valueOf(resultSet.getString("dish_type")));
                dish.setPrice(resultSet.getObject("dish_price") == null
                        ? null : resultSet.getDouble("dish_price"));
                dish.setDishIngredients(findIngredientByDishId(id));
                return dish;
            }
            dbConnection.closeConnection(connection);
            throw new RuntimeException("Dish not found " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Ingredient saveIngredient(Ingredient toSave) {
        String upsertIngredientSql = """
                    INSERT INTO ingredient (id, name, price, category)
                    VALUES (?, ?, ?, ?::dish_type)
                    ON CONFLICT (id) DO UPDATE
                    SET name = EXCLUDED.name,
                        category = EXCLUDED.category,
                        price = EXCLUDED.price
                    RETURNING id
                """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer ingredientId;
            try (PreparedStatement ps = conn.prepareStatement(upsertIngredientSql)) {
                if (toSave.getId() != null) {
                    ps.setInt(1, toSave.getId());
                } else {
                    ps.setInt(1, getNextSerialValue(conn, "ingredient", "id"));
                }
                if (toSave.getPrice() != null) {
                    ps.setDouble(2, toSave.getPrice());
                } else {
                    ps.setNull(2, Types.DOUBLE);
                }
                ps.setString(3, toSave.getName());
                ps.setString(4, toSave.getCategory().name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ingredientId = rs.getInt(1);
                }
            }

            insertIngredientStockMovements(conn, toSave);

            conn.commit();
            return findIngredientById(ingredientId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertIngredientStockMovements(Connection conn, Ingredient ingredient) {
        List<StockMovement> stockMovementList = ingredient.getStockMovementList();
        String sql = """
                insert into stock_movement(id, id_ingredient, quantity, type, unit, creation_datetime)
                values (?, ?, ?, ?::movement_type, ?::unit, ?)
                on conflict (id) do nothing
                """;
        try {
            PreparedStatement preparedStatement = conn.prepareStatement(sql);
            for (StockMovement stockMovement : stockMovementList) {
                if (ingredient.getId() != null) {
                    preparedStatement.setInt(1, ingredient.getId());
                } else {
                    preparedStatement.setInt(1, getNextSerialValue(conn, "stock_movement", "id"));
                }
                preparedStatement.setInt(2, ingredient.getId());
                preparedStatement.setDouble(3, stockMovement.getValue().getQuantity());
                preparedStatement.setObject(4, stockMovement.getType());
                preparedStatement.setObject(5, stockMovement.getValue().getUnit());
                preparedStatement.setTimestamp(6, Timestamp.from(stockMovement.getCreationDatetime()));
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Ingredient findIngredientById(Integer id) {
        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("select id, name, price, category from ingredient where id = ?;");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int idIngredient = resultSet.getInt("id");
                String name = resultSet.getString("name");
                CategoryEnum category = CategoryEnum.valueOf(resultSet.getString("category"));
                Double price = resultSet.getDouble("price");
                return new Ingredient(idIngredient, name, category, price, findStockMovementsByIngredientId(idIngredient));
            }
            throw new RuntimeException("Ingredient not found " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    List<StockMovement> findStockMovementsByIngredientId(Integer id) {

        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<StockMovement> stockMovementList = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select id, quantity, unit, type, creation_datetime
                            from stock_movement
                            where stock_movement.id_ingredient = ?;
                            """);
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                StockMovement stockMovement = new StockMovement();
                stockMovement.setId(resultSet.getInt("id"));
                stockMovement.setType(MovementTypeEnum.valueOf(resultSet.getString("type")));
                stockMovement.setCreationDatetime(resultSet.getTimestamp("creation_datetime").toInstant());

                StockValue stockValue = new StockValue();
                stockValue.setQuantity(resultSet.getDouble("quantity"));
                stockValue.setUnit(Unit.valueOf(resultSet.getString("unit")));
                stockMovement.setValue(stockValue);

                stockMovementList.add(stockMovement);
            }
            dbConnection.closeConnection(connection);
            return stockMovementList;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    Dish saveDish(Dish toSave) {
        String upsertDishSql = """
                    INSERT INTO dish (id, selling_price, name, dish_type)
                    VALUES (?, ?, ?, ?::dish_type)
                    ON CONFLICT (id) DO UPDATE
                    SET name = EXCLUDED.name,
                        dish_type = EXCLUDED.dish_type,
                        selling_price = EXCLUDED.selling_price
                    RETURNING id
                """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer dishId;
            try (PreparedStatement ps = conn.prepareStatement(upsertDishSql)) {
                if (toSave.getId() != null) {
                    ps.setInt(1, toSave.getId());
                } else {
                    ps.setInt(1, getNextSerialValue(conn, "dish", "id"));
                }
                if (toSave.getPrice() != null) {
                    ps.setDouble(2, toSave.getPrice());
                } else {
                    ps.setNull(2, Types.DOUBLE);
                }
                ps.setString(3, toSave.getName());
                ps.setString(4, toSave.getDishType().name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    dishId = rs.getInt(1);
                }
            }

            List<DishIngredient> newDishIngredients = toSave.getDishIngredients();
            detachIngredients(conn, newDishIngredients);
            attachIngredients(conn, newDishIngredients);

            conn.commit();
            return findDishById(dishId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Ingredient> createIngredients(List<Ingredient> newIngredients) {
        if (newIngredients == null || newIngredients.isEmpty()) {
            return List.of();
        }
        List<Ingredient> savedIngredients = new ArrayList<>();
        DBConnection dbConnection = new DBConnection();
        Connection conn = dbConnection.getConnection();
        try {
            conn.setAutoCommit(false);
            String insertSql = """
                        INSERT INTO ingredient (id, name, category, price)
                        VALUES (?, ?, ?::ingredient_category, ?)
                        RETURNING id
                    """;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Ingredient ingredient : newIngredients) {
                    if (ingredient.getId() != null) {
                        ps.setInt(1, ingredient.getId());
                    } else {
                        ps.setInt(1, getNextSerialValue(conn, "ingredient", "id"));
                    }
                    ps.setString(2, ingredient.getName());
                    ps.setString(3, ingredient.getCategory().name());
                    ps.setDouble(4, ingredient.getPrice());

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        int generatedId = rs.getInt(1);
                        ingredient.setId(generatedId);
                        savedIngredients.add(ingredient);
                    }
                }
                conn.commit();
                return savedIngredients;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            dbConnection.closeConnection(conn);
        }
    }


    private void detachIngredients(Connection conn, List<DishIngredient> dishIngredients) {
        Map<Integer, List<DishIngredient>> dishIngredientsGroupByDishId = dishIngredients.stream()
                .collect(Collectors.groupingBy(dishIngredient -> dishIngredient.getDish().getId()));
        dishIngredientsGroupByDishId.forEach((dishId, dishIngredientList) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM dish_ingredient where id_dish = ?")) {
                ps.setInt(1, dishId);
                ps.executeUpdate(); // TODO: must be a grouped by batch
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void attachIngredients(Connection conn, List<DishIngredient> ingredients)
            throws SQLException {

        if (ingredients == null || ingredients.isEmpty()) {
            return;
        }
        String attachSql = """
                    insert into dish_ingredient (id, id_ingredient, id_dish, required_quantity, unit)
                    values (?, ?, ?, ?, ?::unit)
                """;

        try (PreparedStatement ps = conn.prepareStatement(attachSql)) {
            for (DishIngredient dishIngredient : ingredients) {
                ps.setInt(1, getNextSerialValue(conn, "dish_ingredient", "id"));
                ps.setInt(2, dishIngredient.getIngredient().getId());
                ps.setInt(3, dishIngredient.getDish().getId());
                ps.setDouble(4, dishIngredient.getQuantity());
                ps.setObject(5, dishIngredient.getUnit());
                ps.addBatch(); // Can be substitute ps.executeUpdate() but bad performance
            }
            ps.executeBatch();
        }
    }

    private List<DishIngredient> findIngredientByDishId(Integer idDish) {
        DBConnection dbConnection = new DBConnection();
        Connection connection = dbConnection.getConnection();
        List<DishIngredient> dishIngredients = new ArrayList<>();
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    """
                            select ingredient.id, ingredient.name, ingredient.price, ingredient.category, di.required_quantity, di.unit
                            from ingredient join dish_ingredient di on di.id_ingredient = ingredient.id where id_dish = ?;
                            """);
            preparedStatement.setInt(1, idDish);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Ingredient ingredient = new Ingredient();
                ingredient.setId(resultSet.getInt("id"));
                ingredient.setName(resultSet.getString("name"));
                ingredient.setPrice(resultSet.getDouble("price"));
                ingredient.setCategory(CategoryEnum.valueOf(resultSet.getString("category")));

                DishIngredient dishIngredient = new DishIngredient();
                dishIngredient.setIngredient(ingredient);
                dishIngredient.setQuantity(resultSet.getObject("required_quantity") == null ? null : resultSet.getDouble("required_quantity"));
                dishIngredient.setUnit(Unit.valueOf(resultSet.getString("unit")));

                dishIngredients.add(dishIngredient);
            }
            dbConnection.closeConnection(connection);
            return dishIngredients;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    private String getSerialSequenceName(Connection conn, String tableName, String columnName)
            throws SQLException {

        String sql = "SELECT pg_get_serial_sequence(?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    private int getNextSerialValue(Connection conn, String tableName, String columnName)
            throws SQLException {

        String sequenceName = getSerialSequenceName(conn, tableName, columnName);
        if (sequenceName == null) {
            throw new IllegalArgumentException(
                    "Any sequence found for " + tableName + "." + columnName
            );
        }
        updateSequenceNextValue(conn, tableName, columnName, sequenceName);

        String nextValSql = "SELECT nextval(?)";

        try (PreparedStatement ps = conn.prepareStatement(nextValSql)) {
            ps.setString(1, sequenceName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void updateSequenceNextValue(Connection conn, String tableName, String columnName, String sequenceName) throws SQLException {
        String setValSql = String.format(
                "SELECT setval('%s', (SELECT COALESCE(MAX(%s), 0) FROM %s))",
                sequenceName, columnName, tableName
        );

        try (PreparedStatement ps = conn.prepareStatement(setValSql)) {
            ps.executeQuery();
        }
    }

    public Order saveOrder(Order toSave) {
        if (toSave.getId() != null) {
            Order existingOrder = findOrderById(toSave.getId());

            if (existingOrder.getPaymentStatus() == PaymentStatusEnum.PAID) {
                // Vérifier si on tente de modifier la commande
                if (isOrderModified(existingOrder, toSave)) {
                    throw new RuntimeException(
                            "Cannot modify order id=" + toSave.getId() +
                                    " because it has already been paid (PAID status). " +
                                    "Paid orders cannot be modified."
                    );
                }
            }
        }

        String upsertOrderSql = """
            INSERT INTO "order" (id, reference, creation_datetime, payment_status, id_sale)
            VALUES (?, ?, ?, ?::payment_status, ?)
            ON CONFLICT (id) DO UPDATE
            SET reference = EXCLUDED.reference,
                creation_datetime = EXCLUDED.creation_datetime,
                payment_status = EXCLUDED.payment_status,
                id_sale = EXCLUDED.id_sale
            RETURNING id
            """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer orderId;

            try (PreparedStatement ps = conn.prepareStatement(upsertOrderSql)) {
                if (toSave.getId() != null) {
                    ps.setInt(1, toSave.getId());
                } else {
                    ps.setInt(1, getNextSerialValue(conn, "order", "id"));
                }
                ps.setString(2, toSave.getReference());

                if (toSave.getCreationDatetime() != null) {
                    ps.setTimestamp(3, Timestamp.from(toSave.getCreationDatetime()));
                } else {
                    ps.setTimestamp(3, Timestamp.from(Instant.now()));
                }

                ps.setString(4, toSave.getPaymentStatus().name());

                if (toSave.getSale() != null && toSave.getSale().getId() != null) {
                    ps.setInt(5, toSave.getSale().getId());
                } else {
                    ps.setNull(5, Types.INTEGER);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    orderId = rs.getInt(1);
                }
            }

            if (toSave.getId() == null ||
                    (toSave.getId() != null && findOrderById(toSave.getId()).getPaymentStatus() != PaymentStatusEnum.PAID)) {
                saveDishOrdersForOrder(conn, toSave, orderId);
            }

            conn.commit();
            return findOrderById(orderId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isOrderModified(Order existingOrder, Order newOrder) {
        if (!Objects.equals(existingOrder.getReference(), newOrder.getReference())) {
            return true;
        }

        if (!Objects.equals(existingOrder.getCreationDatetime(), newOrder.getCreationDatetime())) {
            return true;
        }

        return areDishOrdersModified(existingOrder.getDishOrders(), newOrder.getDishOrders());
    }

    private boolean areDishOrdersModified(List<DishOrder> existingDishOrders, List<DishOrder> newDishOrders) {
        if (existingDishOrders == null && newDishOrders == null) {
            return false;
        }

        if ((existingDishOrders == null && newDishOrders != null) ||
                (existingDishOrders != null && newDishOrders == null)) {
            return true;
        }

        if (existingDishOrders.size() != newDishOrders.size()) {
            return true;
        }

        for (int i = 0; i < existingDishOrders.size(); i++) {
            DishOrder existing = existingDishOrders.get(i);
            DishOrder newDishOrder = newDishOrders.get(i);

            if (!Objects.equals(existing.getId(), newDishOrder.getId()) ||
                    !Objects.equals(existing.getDish().getId(), newDishOrder.getDish().getId()) ||
                    !Objects.equals(existing.getQuantity(), newDishOrder.getQuantity())) {
                return true;
            }
        }

        return false;
    }

    private void saveDishOrdersForOrder(Connection conn, Order order, Integer orderId) throws SQLException {
        if (order.getId() != null) {
            Order existingOrder = findOrderById(order.getId());
            if (existingOrder.getPaymentStatus() == PaymentStatusEnum.PAID) {
                throw new RuntimeException(
                        "Cannot modify dish orders for order id=" + order.getId() +
                                " because it has already been paid (PAID status)."
                );
            }
        }

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dish_order WHERE id_order = ?")) {
            ps.setInt(1, orderId);
            ps.executeUpdate();
        }

        if (order.getDishOrders() != null && !order.getDishOrders().isEmpty()) {
            String insertDishOrderSql = """
                INSERT INTO dish_order (id, id_order, id_dish, quantity)
                VALUES (?, ?, ?, ?)
                """;

            try (PreparedStatement ps = conn.prepareStatement(insertDishOrderSql)) {
                for (DishOrder dishOrder : order.getDishOrders()) {
                    ps.setInt(1, getNextSerialValue(conn, "dish_order", "id"));
                    ps.setInt(2, orderId);
                    ps.setInt(3, dishOrder.getDish().getId());
                    ps.setInt(4, dishOrder.getQuantity());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private Order findOrderById(Integer id) {
        DBConnection dbConnection = new DBConnection();
        try (Connection connection = dbConnection.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("""
                select o.id, o.reference, o.creation_datetime, o.payment_status, o.id_sale,
                       s.creation_datetime as sale_creation_datetime
                from "order" o
                left join sale s on o.id_sale = s.id
                where o.id = ?""");
            preparedStatement.setInt(1, id);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                Order order = new Order();
                Integer idOrder = resultSet.getInt("id");
                order.setId(idOrder);
                order.setReference(resultSet.getString("reference"));
                order.setCreationDatetime(resultSet.getTimestamp("creation_datetime").toInstant());
                order.setPaymentStatus(PaymentStatusEnum.valueOf(resultSet.getString("payment_status")));

                Integer saleId = resultSet.getObject("id_sale") != null ?
                        resultSet.getInt("id_sale") : null;
                if (saleId != null) {
                    Sale sale = new Sale();
                    sale.setId(saleId);
                    sale.setCreationDatetime(resultSet.getTimestamp("sale_creation_datetime").toInstant());
                    sale.setOrder(order);
                    order.setSale(sale);
                }

                order.setDishOrders(findDishOrderByIdOrder(idOrder));
                return order;
            }
            throw new RuntimeException("Order not found with id " + id);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Sale createSaleFrom(Order order) {
        if (order.getPaymentStatus() != PaymentStatusEnum.PAID) {
            throw new RuntimeException("Cannot create sale from unpaid order. Order payment status: " + order.getPaymentStatus());
        }

        if (order.getId() == null) {
            throw new RuntimeException("Order must be saved before creating a sale");
        }

        if (order.getSale() != null) {
            throw new RuntimeException("Sale already exists for order id: " + order.getId());
        }

        String insertSaleSql = """
            INSERT INTO sale (id, creation_datetime)
            VALUES (?, ?)
            ON CONFLICT (id) DO NOTHING
            RETURNING id
            """;

        try (Connection conn = new DBConnection().getConnection()) {
            conn.setAutoCommit(false);
            Integer saleId;

            try (PreparedStatement ps = conn.prepareStatement(insertSaleSql)) {
                int nextSaleId = getNextSerialValue(conn, "sale", "id");
                ps.setInt(1, nextSaleId);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        saleId = rs.getInt(1);
                    } else {
                        conn.rollback();
                        return null;
                    }
                }
            }

            String updateOrderSql = """
                UPDATE "order" 
                SET id_sale = ? 
                WHERE id = ? AND id_sale IS NULL
                """;

            try (PreparedStatement ps = conn.prepareStatement(updateOrderSql)) {
                ps.setInt(1, saleId);
                ps.setInt(2, order.getId());
                int rowsUpdated = ps.executeUpdate();

                if (rowsUpdated == 0) {
                    conn.rollback();
                    throw new RuntimeException("Order already has a sale or order not found");
                }
            }

            conn.commit();

            Sale sale = new Sale();
            sale.setId(saleId);
            sale.setCreationDatetime(Instant.now());
            sale.setOrder(order);

            return sale;
        } catch (SQLException e) {
            throw new RuntimeException("Error creating sale: " + e.getMessage(), e);
        }
    }
}

