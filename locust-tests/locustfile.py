from locust import HttpLocust, TaskSet, task, TaskSequence, seq_task
import requests, re, random

USER_PORT = '8080'
STOCK_PORT = '8081'
ORDER_PORT = '8082'
PAYMENT_PORT = '8083'

USER_LB_URI="http://UserLB-78627843.eu-central-1.elb.amazonaws.com:8080"
STOCK_LB_URI="http://StockLB-1528496191.eu-central-1.elb.amazonaws.com:8081"
ORDER_LB_URI="http://OrderLB-2122101751.eu-central-1.elb.amazonaws.com:8082"
PAYMENT_LB_URI="http://PaymentLB-1003200854.eu-central-1.elb.amazonaws.com:8083"


# Users Service
def create_user(locust_user):
    locust_user.client.base_url = USER_LB_URI
    #response = locust_user.client.post("%s/users/create" %(USER_LB_URI))
    response = locust_user.client.post("/users/create", catch_response=True)
    try:
        locust_user.user_id = re.search('\((.+?)\)', response.text).group(1)
        response.success()
    except AttributeError:
        response.failure(response.text)
        create_user(locust_user)

def remove_user(locust_user):
    locust_user.client.base_url = USER_LB_URI
    response = locust_user.client.delete("/users/remove/%s" %(locust_user.user_id))
    return response.text

def find_user(locust_user):
    locust_user.client.base_url = USER_LB_URI
    response = locust_user.client.get("/users/find/%s" %(locust_user.user_id))
    return response.text

def get_credit(locust_user):
    locust_user.client.base_url = USER_LB_URI
    response = locust_user.client.get("/users/credit/%s" %(locust_user.user_id), catch_response=True, name="/users/credit/")
    try:
        credit = re.search(',(.+?)\)', response.text).group(1)
        response.success()
        return credit
    except AttributeError:
        response.failure(response.text)
        return "unknown"

def subtract_credit(locust_user, credit):
    locust_user.client.base_url = USER_LB_URI
    response = locust_user.client.post("/users/credit/subtract/%s/%i" %(locust_user.user_id, credit), name="/users/credit/subtract/")
    if response.text[:16] == 'CreditSubtracted':
        operation_performed = re.search(',true,', response.text)
        if operation_performed is not None:
            locust_user.credit -= credit
        else:
            print(response.text)
    return response.text

def add_credit(locust_user, credit):
    locust_user.client.base_url = USER_LB_URI
    response = locust_user.client.post("/users/credit/add/%s/%i" %(locust_user.user_id, credit), name="/users/credit/add/")
    if response.text[:11] == 'CreditAdded':
        operation_performed = re.search(',true,', response.text)
        if operation_performed is not None:
            locust_user.credit += credit
        else:
            print(response.text)
    return response.text

# Order Service
def create_order(locust_user):
    locust_user.client.base_url = ORDER_LB_URI
    response = locust_user.client.post("/orders/create/%s" %(locust_user.user_id), catch_response=True, name="/orders/create/")
    try:
        order_id = re.search('\((.+?),', response.text).group(1)
        if response.text[:12] == 'OrderCreated':
            locust_user.my_orders.append(order_id)
            response.success()
            return order_id
    except AttributeError:
        response.failure(response.text)
    

def remove_order(locust_user, order_id):
    locust_user.client.base_url = ORDER_LB_URI
    response = locust_user.client.delete("/orders/remove/%s" %(order_id))
    if response.text[:12] == 'OrderDeleted':
        try:
            locust_user.my_orders.remove(order_id)
        except ValueError:
            print('removed an order that was not mine')
    return response.text

def find_order(locust_user, order_id):
    locust_user.client.base_url = ORDER_LB_URI
    response = locust_user.client.get("/orders/find/%s" %(order_id))
    return response.text

def order_add_item(locust_user, order_id, item_id):
    locust_user.client.base_url = ORDER_LB_URI
    #response = locust_user.client.post("%s/orders/addItem/%s/%s" %(ORDER_LB_URI, order_id, item_id))
    response = locust_user.client.post("/orders/item/add/%s/%s" %(order_id, item_id) , name="/orders/item/add/")

def order_remove_item(locust_user, order_id, item_id):
    locust_user.client.base_url = ORDER_LB_URI
    #response = locust_user.client.post("%s/orders/removeItem/%s/%s" %(ORDER_LB_URI, order_id, item_id))
    response = locust_user.client.post("/orders/item/remove/%s/%s" %(order_id, item_id))

def checkout_order(locust_user, order_id):
    locust_user.client.base_url = ORDER_LB_URI
    response = locust_user.client.post("/orders/checkout/%s" %(order_id))
    if response.status_code == 200:
        try:
            locust_user.my_orders.remove(order_id)
            #we also have to substract credit
        except ValueError:
            print('removed an order that was not mine')
    return response.text

# Stock Service
def stock_availability(locust_user, item_id):
    locust_user.client.base_url = STOCK_LB_URI
    return locust_user.client.get("/stock/availability/%s" %(item_id))
    
def add_stock(locust_user, item_id, quantity):
    locust_user.client.base_url = STOCK_LB_URI
    return locust_user.client.post("/stock/add/%s/%s" %(item_id, quantity), name="/stock/add/")

def subtract_stock(locust_user, item_id, quantity):
    locust_user.client.base_url = STOCK_LB_URI
    return locust_user.client.post("/stock/subtract/%s/%s" %(item_id, quantity))

def create_item(locust_user):
    locust_user.client.base_url = STOCK_LB_URI
    response = locust_user.client.post("/stock/item/create", catch_response=True)
    try:
        item_id = re.search('\((.+?)\)', response.text).group(1)
        response.success()
        return item_id
    except AttributeError:
        response.failure(response.text)
        return None

# aux functions
def populate_items(locust_user):
    items_created = []
    for i in range(random.randint(5, 10)):
        new_item = create_item(locust_user)
        if new_item is not None:
            add_stock(locust_user, new_item, 1000)
            items_created.append(new_item)
    return items_created


class UserBehavior(TaskSet):
    def on_start(self):
        """ on_start is called when a Locust start before any task is scheduled """
        create_user(self)
        self.credit = 0
        self.my_orders = []
        self.items_available = populate_items(self)
        add_credit(self, random.randint(1000000, 5000000))

    def on_stop(self):
        print('my credit: {}, credit in db: {}'.format(self.credit, get_credit(self)))

    @task
    def add(self):
        credit = random.randint(1, 10)
        add_credit(self, credit)

    @task
    def sub(self):
        credit = random.randint(1, 10)
        subtract_credit(self, credit)
    
    @task
    class make_order(TaskSequence):
        @seq_task(1)
        def create_new_order(self):
            self.actual_order = create_order(self.parent)
            if self.actual_order is None:
                self.interrupt() 
                
        @seq_task(2)
        @task(random.randint(1, 5))
        def populate_order(self):
            item = random.choice(self.parent.items_available)
            order_add_item(self.parent, self.actual_order, item)
        '''
        @seq_task(3)
        def checkout(self):
            checkout_order(self.parent, self.actual_order)
        '''
class WebsiteUser(HttpLocust):
    task_set = UserBehavior
    min_wait = 500
    max_wait = 900